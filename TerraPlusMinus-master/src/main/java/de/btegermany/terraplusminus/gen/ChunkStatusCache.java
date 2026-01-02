package de.btegermany.terraplusminus.gen;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ChunkStatusCache {
    // Zbiór przechowujący koordynaty chunków, które nie załadowały się poprawnie
    private static final Set<Long> failed = Collections.synchronizedSet(new HashSet<>());

    // Konwertuje X i Z na unikalny klucz Long
    private static long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public static void markAsFailed(int x, int z) {
        failed.add(getChunkKey(x, z));
    }

    public static boolean isFailed(int x, int z) {
        return failed.contains(getChunkKey(x, z));
    }

    // Ta metoda usuwa błąd z listy
    public static void removeFailure(int x, int z) {
        failed.remove(getChunkKey(x, z));
    }

    // Czyści cały cache (przydatne przy przeładowaniu pluginu)
    public static void clearAll() {
        failed.clear();
    }
}