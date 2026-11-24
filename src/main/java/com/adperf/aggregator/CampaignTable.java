package com.adperf.aggregator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Running totals per campaign, keyed by the raw bytes of the campaign id.
 *
 * <p>A {@code HashMap<String, ...>} would need a new String for every row just to look the key up. This
 * open-addressing table hashes and compares the id directly in the read buffer, so a row allocates nothing;
 * a String is created only once per distinct campaign. Each worker thread owns its own table.
 */
final class CampaignTable {

    private static final int INITIAL_CAPACITY = 64;

    private Entry[] slots = new Entry[INITIAL_CAPACITY];
    private int size;

    void add(byte[] bytes, int idStart, int idEnd, long impressions, long clicks, long spendCents, long conversions) {
        Entry entry = findOrInsert(bytes, idStart, idEnd);
        entry.impressions += impressions;
        entry.clicks += clicks;
        entry.spendCents += spendCents;
        entry.conversions += conversions;
    }

    List<Campaign> campaigns() {
        List<Campaign> campaigns = new ArrayList<>(size);
        for (Entry entry : slots) {
            if (entry != null) {
                campaigns.add(new Campaign(new String(entry.id, StandardCharsets.UTF_8),
                        entry.impressions, entry.clicks, entry.spendCents, entry.conversions));
            }
        }
        return campaigns;
    }

    private Entry findOrInsert(byte[] bytes, int from, int to) {
        int hash = hash(bytes, from, to);
        int mask = slots.length - 1;
        for (int slot = hash & mask; ; slot = (slot + 1) & mask) {
            Entry entry = slots[slot];
            if (entry == null) {
                entry = new Entry(Arrays.copyOfRange(bytes, from, to), hash);
                slots[slot] = entry;
                if (++size * 2 > slots.length) {
                    grow();
                }
                return entry;
            }
            if (entry.hash == hash && Arrays.equals(entry.id, 0, entry.id.length, bytes, from, to)) {
                return entry;
            }
        }
    }

    /** Keeps the table at most half full so probe sequences stay short. */
    private void grow() {
        Entry[] old = slots;
        slots = new Entry[old.length * 2];
        int mask = slots.length - 1;
        for (Entry entry : old) {
            if (entry != null) {
                int slot = entry.hash & mask;
                while (slots[slot] != null) {
                    slot = (slot + 1) & mask;
                }
                slots[slot] = entry;
            }
        }
    }

    private static int hash(byte[] bytes, int from, int to) {
        int hash = 1;
        for (int i = from; i < to; i++) {
            hash = 31 * hash + bytes[i];
        }
        return hash ^ (hash >>> 16);
    }

    private static final class Entry {
        final byte[] id;
        final int hash;
        long impressions;
        long clicks;
        long spendCents;
        long conversions;

        Entry(byte[] id, int hash) {
            this.id = id;
            this.hash = hash;
        }
    }
}
