package moclj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PersistentVectorTest {

    private static PersistentVector range(int size) {
        PersistentVector vector = PersistentVector.EMPTY;
        for (int i = 0; i < size; i++) {
            vector = vector.conj((long) i);
        }
        return vector;
    }

    @Test
    void holdsElementsAcrossEveryTreeLevel() {
        // 32^3 + 1 elements, hence a root, two interior levels and the leaves.
        PersistentVector vector = range(32_769);
        assertEquals(32_769, vector.size());
        for (int index : List.of(0, 31, 32, 1023, 1024, 32_767, 32_768)) {
            assertEquals((long) index, vector.nth(index));
        }
    }

    @Test
    void conjLeavesTheOlderVersionAlone() {
        PersistentVector v1 = range(100);
        PersistentVector v2 = v1.conj("appended");

        assertEquals(100, v1.size());
        assertEquals(101, v2.size());
        assertEquals("appended", v2.nth(100));
        assertThrows(IndexOutOfBoundsException.class, () -> v1.nth(100));
    }

    @Test
    void conjSharesEverySubtreeItDoesNotTouch() {
        // Two levels, so the leaves hang off the root and only the last one is
        // rebuilt when appending.
        PersistentVector v1 = range(1000);
        PersistentVector v2 = v1.conj(1000L);

        Object[] before = v1.root().array();
        Object[] after = v2.root().array();
        assertNotEquals(before[31], after[31]);
        for (int slot = 0; slot < 31; slot++) {
            assertSame(before[slot], after[slot], "slot " + slot + " was copied instead of shared");
        }
    }

    @Test
    void growingPastTheRootCapacityAddsALevel() {
        PersistentVector full = range(32);
        PersistentVector deeper = full.conj(32L);

        assertSame(full.root(), deeper.root().array()[0]);
        assertEquals(32L, deeper.nth(32));
        assertEquals(0L, deeper.nth(0));
    }

    @Test
    void assocReplacesOneElementAndAppendsAtTheEnd() {
        PersistentVector v1 = range(64);
        PersistentVector replaced = v1.assoc(40, "x");

        assertEquals("x", replaced.nth(40));
        assertEquals(40L, v1.nth(40));
        assertEquals(64, replaced.size());
        assertEquals(65, v1.assoc(64, "appended").size());
        assertThrows(IndexOutOfBoundsException.class, () -> v1.assoc(65, "x"));
    }

    @Test
    void indexesOutsideTheVectorAreRejected() {
        PersistentVector vector = range(3);
        assertThrows(IndexOutOfBoundsException.class, () -> vector.nth(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> vector.nth(3));
    }

    @Test
    void behavesAsAnImmutableList() {
        PersistentVector vector = PersistentVector.of(1L, 2L, 3L);
        assertEquals(List.of(1L, 2L, 3L), vector);
        assertEquals(List.of(1L, 2L, 3L).hashCode(), vector.hashCode());
        assertEquals("[1 2 3]", vector.toString());
        assertEquals("[]", PersistentVector.EMPTY.toString());
        assertThrows(UnsupportedOperationException.class, () -> vector.add("nope"));
    }

    @Test
    void aMillionElementVectorStaysCheapToExtend() {
        PersistentVector v1 = range(1_000_000);
        PersistentVector v2 = v1.conj("New_Item_Million");

        assertEquals(999_999L, v1.nth(999_999));
        assertEquals("New_Item_Million", v2.nth(1_000_000));
        assertEquals(1_000_000, v1.size());
        // Four levels, so appending rebuilt four nodes; the rest is shared.
        assertTrue(
                v1.root().array()[0] == v2.root().array()[0] && v1.root().array()[1] == v2.root().array()[1],
                "subtrees of a full vector should survive an append");
    }
}
