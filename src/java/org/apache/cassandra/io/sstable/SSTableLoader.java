/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.io.sstable;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.streaming.*;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.OutputHandler;
import org.apache.cassandra.utils.Pair;

/**
 * Cassandra SSTable bulk loader.
 * Load an externally created sstable into a cluster.
 */
public class SSTableLoader implements StreamEventHandler
{
    private final File directory;
    private final String keyspace;
    private final Client client;
    private final OutputHandler outputHandler;
    private final Set<InetAddress> failedHosts = new HashSet<>();

    private final List<SSTableReader> sstables = new ArrayList<SSTableReader>();
    private final Multimap<InetAddress, StreamSession.SSTableStreamingSections> streamingDetails = HashMultimap.create();

    static
    {
        Config.setClientMode(true);
    }

    public SSTableLoader(File directory, Client client, OutputHandler outputHandler)
    {
        this.directory = directory;
        this.keyspace = directory.getParentFile().getName();
        this.client = client;
        this.outputHandler = outputHandler;
    }

    protected Collection<SSTableReader> openSSTables(final Map<InetAddress, Collection<Range<Token>>> ranges)
    {
        outputHandler.output("Opening sstables and calculating sections to stream");

        directory.list(new FilenameFilter()
        {
            public boolean accept(File dir, String name)
            {
                if (new File(dir, name).isDirectory())
                    return false;
                Pair<Descriptor, Component> p = SSTable.tryComponentFromFilename(dir, name);
                Descriptor desc = p == null ? null : p.left;
                if (p == null || !p.right.equals(Component.DATA) || desc.temporary)
                    return false;

                if (!new File(desc.filenameFor(Component.PRIMARY_INDEX)).exists())
                {
                    outputHandler.output(String.format("Skipping file %s because index is missing", name));
                    return false;
                }

                CFMetaData metadata = client.getCFMetaData(keyspace, desc.cfname);
                if (metadata == null)
                {
                    outputHandler.output(String.format("Skipping file %s: column family %s.%s doesn't exist", name, keyspace, desc.cfname));
                    return false;
                }

                Set<Component> components = new HashSet<Component>();
                components.add(Component.DATA);
                components.add(Component.PRIMARY_INDEX);
                if (new File(desc.filenameFor(Component.SUMMARY)).exists())
                    components.add(Component.SUMMARY);
                if (new File(desc.filenameFor(Component.COMPRESSION_INFO)).exists())
                    components.add(Component.COMPRESSION_INFO);
                if (new File(desc.filenameFor(Component.STATS)).exists())
                    components.add(Component.STATS);

                try
                {
                    // To conserve memory, open SSTableReaders without bloom filters and discard
                    // the index summary after calculating the file sections to stream and the estimated
                    // number of keys for each endpoint. See CASSANDRA-5555 for details.
                    SSTableReader sstable = SSTableReader.openForBatch(desc, components, metadata, client.getPartitioner());
                    sstables.add(sstable);

                    // calculate the sstable sections to stream as well as the estimated number of
                    // keys per host
                    for (Map.Entry<InetAddress, Collection<Range<Token>>> entry : ranges.entrySet())
                    {
                        InetAddress endpoint = entry.getKey();
                        Collection<Range<Token>> tokenRanges = entry.getValue();

                        List<Pair<Long, Long>> sstableSections = sstable.getPositionsForRanges(tokenRanges);
                        long estimatedKeys = sstable.estimatedKeysForRanges(tokenRanges);

                        StreamSession.SSTableStreamingSections details = new StreamSession.SSTableStreamingSections(sstable, sstableSections, estimatedKeys, ActiveRepairService.UNREPAIRED_SSTABLE);
                        streamingDetails.put(endpoint, details);
                    }

                    // to conserve heap space when bulk loading
                    sstable.releaseSummary();
                }
                catch (IOException e)
                {
                    outputHandler.output(String.format("Skipping file %s, error opening it: %s", name, e.getMessage()));
                }
                return false;
            }
        });
        return sstables;
    }

    public StreamResultFuture stream()
    {
        return stream(Collections.<InetAddress>emptySet());
    }

    public StreamResultFuture stream(Set<InetAddress> toIgnore, StreamEventHandler... listeners)
    {
        client.init(keyspace);
        outputHandler.output("Established connection to initial hosts");

        StreamPlan plan = new StreamPlan("Bulk Load");

        Map<InetAddress, Collection<Range<Token>>> endpointToRanges = client.getEndpointToRangesMap();
        openSSTables(endpointToRanges);
        if (sstables.isEmpty())
        {
            // return empty result
            return plan.execute();
        }

        outputHandler.output(String.format("Streaming relevant part of %sto %s", names(sstables), endpointToRanges.keySet()));

        for (Map.Entry<InetAddress, Collection<Range<Token>>> entry : endpointToRanges.entrySet())
        {
            InetAddress remote = entry.getKey();
            if (toIgnore.contains(remote))
                continue;

            Collection<StreamSession.SSTableStreamingSections> endpointDetails = streamingDetails.get(remote);

            // transferSSTables assumes references have been acquired
            for (StreamSession.SSTableStreamingSections details : endpointDetails)
                details.sstable.acquireReference();

            plan.transferFiles(remote, streamingDetails.get(remote));
        }
        plan.listeners(this, listeners);
        return plan.execute();
    }

    public void onSuccess(StreamState finalState) {}
    public void onFailure(Throwable t) {}

    public void handleStreamEvent(StreamEvent event)
    {
        if (event.eventType == StreamEvent.Type.STREAM_COMPLETE)
        {
            StreamEvent.SessionCompleteEvent se = (StreamEvent.SessionCompleteEvent) event;
            if (!se.success)
                failedHosts.add(se.peer);
        }
    }

    private String names(Collection<SSTableReader> sstables)
    {
        StringBuilder builder = new StringBuilder();
        for (SSTableReader sstable : sstables)
            builder.append(sstable.descriptor.filenameFor(Component.DATA)).append(" ");
        return builder.toString();
    }

    public Set<InetAddress> getFailedHosts()
    {
        return failedHosts;
    }

    public static abstract class Client
    {
        private final Map<InetAddress, Collection<Range<Token>>> endpointToRanges = new HashMap<InetAddress, Collection<Range<Token>>>();
        private IPartitioner partitioner;

        /**
         * Initialize the client.
         * Perform any step necessary so that after the call to the this
         * method:
         *   * partitioner is initialized
         *   * getEndpointToRangesMap() returns a correct map
         * This method is guaranteed to be called before any other method of a
         * client.
         */
        public abstract void init(String keyspace);

        /**
         * Stop the client.
         */
        public void stop() {}

        /**
         * Validate that {@code keyspace} is an existing keyspace and {@code
         * cfName} one of its existing column family.
         */
        public abstract CFMetaData getCFMetaData(String keyspace, String cfName);

        public Map<InetAddress, Collection<Range<Token>>> getEndpointToRangesMap()
        {
            return endpointToRanges;
        }

        protected void setPartitioner(String partclass) throws ConfigurationException
        {
            setPartitioner(FBUtilities.newPartitioner(partclass));
        }

        protected void setPartitioner(IPartitioner partitioner)
        {
            this.partitioner = partitioner;
            DatabaseDescriptor.setPartitioner(partitioner);
        }

        public IPartitioner getPartitioner()
        {
            return partitioner;
        }

        protected void addRangeForEndpoint(Range<Token> range, InetAddress endpoint)
        {
            Collection<Range<Token>> ranges = endpointToRanges.get(endpoint);
            if (ranges == null)
            {
                ranges = new HashSet<Range<Token>>();
                endpointToRanges.put(endpoint, ranges);
            }
            ranges.add(range);
        }
    }
}
