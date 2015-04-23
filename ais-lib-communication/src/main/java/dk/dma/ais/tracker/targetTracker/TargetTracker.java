/* Copyright (c) 2011 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dk.dma.ais.tracker.targetTracker;

import dk.dma.ais.message.AisMessage;
import dk.dma.ais.message.AisTargetType;
import dk.dma.ais.packet.AisPacket;
import dk.dma.ais.packet.AisPacketSource;
import dk.dma.ais.tracker.Tracker;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * A simple tracker that keeps track of targets.
 * <p>
 * There are no automatically cleanup instead users must regularly cleanup targets by calling
 * {@link #removeAll(Predicate)}
 * 
 * @author Kasper Nielsen
 * @author Jens Tuxen
 */
public class TargetTracker implements Tracker {

    /** All targets that we are currently monitoring. */
    final ConcurrentHashMap<Integer, MmsiTarget> targets = new ConcurrentHashMap<>();

    /**
     * Returns the number of targets that is being tracked. This is usually a lot faster than invoking
     * <tt>stream(predicate).count()</tt>
     * 
     * @return the number of targets that is being tracked
     */
    public int count(Predicate<? super AisPacketSource> predicate) {
        LongAdder la = new LongAdder();
        targets.values().stream().forEach(t -> {
            for (dk.dma.ais.tracker.targetTracker.TargetInfo i : t.values()) {
                if (predicate.test(i.getPacketSource())) {
                    la.increment();
                    return;
                }
            }
        });
        return la.intValue();
    }

    /**
     * Returns the total number of reports for all targets. Each target might have multiple reports.
     *
     * @return the total number of reports for all targets
     */
    public int countNumberOfReports() {
        LongAdder la = new LongAdder();
        targets.values().forEach(t -> {
            la.add(t.size());
        });
        return la.intValue();
    }

    /**
     * Returns the latest target info for the specified MMSI number.
     *
     * @param mmsi
     *            the MMSI number
     * @return the latest target info for the specified MMSI number
     */
    public dk.dma.ais.tracker.targetTracker.TargetInfo get(int mmsi) {
        return get(mmsi, e -> true);
    }

    public dk.dma.ais.tracker.targetTracker.TargetInfo get(int mmsi, Predicate<? super AisPacketSource> sourcePredicate) {
        MmsiTarget target = targets.get(mmsi);
        return target == null ? null : target.getLatest(sourcePredicate);
    }

    /**
     * Returns a set of all packet sources for a given MMSI number.
     *
     * @param mmsi
     *            the MMSI number
     * @return a set of all packet sources for a given MMSI number
     */
    public Set<AisPacketSource> getPacketSourcesForMMSI(int mmsi) {
        MmsiTarget t = targets.get(mmsi);
        return t == null ? Collections.emptySet() : new HashSet<>(t.keySet());
    }

    /**
     * Removes all targets that are accepted by the specified predicate. Is typically used to remove targets based on
     * time stamps.
     *
     * @param predicate
     *            the predicate that selects which items to remove
     */
    public void removeAll(Predicate<? super dk.dma.ais.tracker.targetTracker.TargetInfo> predicate) {
        requireNonNull(predicate);
        targets.values().stream().forEach(t -> {
            for (dk.dma.ais.tracker.targetTracker.TargetInfo i : t.values()) {
                if (predicate.test(i)) {
                    t.remove(i.getPacketSource(), i);
                }
            }
            // race with update mechanism is handled in #tryUpdate
                if (t.isEmpty()) {
                    targets.remove(t.mmsi, t);
                }
            });
    }

    /**
     * Returns the number of targets that is being tracked.
     *
     * @return the number of targets that is being tracked
     * @see #count(Predicate)
     */
    public int size() {
        return targets.size();
    }

    /**
     * Creates a parallel stream of all targets.
     *
     * @return a stream of targets
     */
    public Stream<dk.dma.ais.tracker.targetTracker.TargetInfo> stream() {
        return stream(e -> true);
    }

    /**
     * Creates a parallel stream of targets with the specified source predicate.
     *
     * @param predicate
     *            the predicate on AIS packet source
     * @return a stream of targets
     */
    public Stream<dk.dma.ais.tracker.targetTracker.TargetInfo> stream(Predicate<? super AisPacketSource> predicate) {
        requireNonNull(predicate, "predicate is null");
        return targets.values().parallelStream().map(t -> t.getLatest(predicate)).filter(e -> e != null);
    }

    /**
     * Creates a sequential stream of all targets.
     *
     * @return a stream of targets
     */
    public Stream<dk.dma.ais.tracker.targetTracker.TargetInfo> streamSequential() {
        return streamSequential(src -> true);
    }

    /**
     * Creates a sequential stream of targets with the specified source predicate.
     *
     * @param sourcePredicate
     *            the predicate on AIS packet source
     * @return a stream of targets
     */
    public Stream<dk.dma.ais.tracker.targetTracker.TargetInfo> streamSequential(Predicate<? super AisPacketSource> sourcePredicate) {
        requireNonNull(sourcePredicate, "sourcePredicate is null");
        return streamSequential(sourcePredicate, target -> true);
    }

    /**
     * Creates a sequential stream of targets with the specified source predicate and matching the given targetPredicate.
     * @param sourcePredicate
     * @param targetPredicate
     * @return a stream of targets
     */
    public Stream<dk.dma.ais.tracker.targetTracker.TargetInfo> streamSequential(Predicate<? super AisPacketSource> sourcePredicate, Predicate<? super TargetInfo> targetPredicate) {
        requireNonNull(targetPredicate, "targetPredicate is null");
        requireNonNull(sourcePredicate, "sourcePredicate is null");
        return targets.values().stream().map(t -> t.getLatest(sourcePredicate)).filter(e -> e != null).filter(targetPredicate);
    }

    /**
     * A little helper method that makes sure we do not get lost updates when updating a target. While the MMSI target
     * is being cleaned.
     *
     * @param mmsi
     *            the MMSI number
     * @param c
     *            a consumer that can use the MMSI target
     */
    private void tryUpdate(int mmsi, Consumer<MmsiTarget> c) {
        for (;;) {
            // Lets first get the target. Or create a new target if it does not
            // currently exist
            MmsiTarget t = targets.computeIfAbsent(mmsi, i -> new MmsiTarget(mmsi));
            c.accept(t);

            // We can get some very rare races with the cleanup method. So we
            // just need to check that the mmsi target we
            // just updated/created is the same now
            if (t == targets.get(mmsi)) {
                return;
            }
        }
    }

    /**
     * Updates the tracker with the specified packet
     *
     * @param packet
     *            the packet to update the trigger with
     */
    public void update(AisPacket packet) {
        AisMessage message = packet.tryGetAisMessage();
        Date date = packet.getTimestamp();

        // We only want to handle messages containing targets data
        // #1-#3, #4, #5, #18, #21, #24 and a valid timestamp
        if (message != null && date != null) {
            // find the target type
            AisTargetType targetType = message.getTargetType();
            // only update if there is a target type
            if (targetType != null) {
                tryUpdate(
                        message.getUserId(),
                        t -> t.compute(
                                AisPacketSource.create(packet),
                                (source, existing) -> dk.dma.ais.tracker.targetTracker.TargetInfo.updateTarget(existing, packet, targetType,
                                        date.getTime(), source, t.msg24Part0)));
            }
        }
    }

    /**
     * Used by the backup routine to restore data.
     *
     * @param packetSource
     *            the source
     * @param targetInfo
     *            the target info
     */
    void update(AisPacketSource packetSource, dk.dma.ais.tracker.targetTracker.TargetInfo targetInfo) {
        tryUpdate(targetInfo.getMmsi(),
                t -> t.merge(packetSource, targetInfo, (ex, newOne) -> ex == null ? newOne : ex.merge(newOne)));
    }

    /**
     * A single ship containing multiple reports for different combinations of sources.
     */
    @SuppressWarnings("serial")
    static class MmsiTarget extends ConcurrentHashMap<AisPacketSource, dk.dma.ais.tracker.targetTracker.TargetInfo> {

        /** The MMSI number */
        final int mmsi;

        /** A cache of AIS messages 24 part 0. */
        final ConcurrentHashMap<AisPacketSource, byte[]> msg24Part0 = new ConcurrentHashMap<>();

        MmsiTarget(int mmsi) {
            this.mmsi = mmsi;
        }

        /**
         * Returns the newest position and static data.
         *
         * @param predicate
         *            a predicate for filtering on the sources
         * @return the newest position and static data
         */
        dk.dma.ais.tracker.targetTracker.TargetInfo getLatest(Predicate<? super AisPacketSource> predicate) {
            // This method is fairly optimized to avoid creating excessive objects.
            dk.dma.ais.tracker.targetTracker.TargetInfo bestStatic = null;
            dk.dma.ais.tracker.targetTracker.TargetInfo bestPosition = null;
            for (TargetInfo i : values()) {
                if (predicate.test(i.getPacketSource())) {
                    if (i.hasStaticInfo()
                            && (bestStatic == null || i.getStaticTimestamp() > bestStatic.getStaticTimestamp())) {
                        bestStatic = i;
                    }
                    if (i.hasPositionInfo()
                            && (bestPosition == null || i.getPositionTimestamp() > bestPosition.getPositionTimestamp())) {
                        bestPosition = i;
                    }
                }
            }
            if (bestStatic == null || bestStatic == bestPosition) {
                return bestPosition;
            } else if (bestPosition == null) {
                return bestStatic;
            } else { // we need to merge two different targets
                return bestPosition.mergeWithStaticFrom(bestStatic);
            }
        }
    }
}
