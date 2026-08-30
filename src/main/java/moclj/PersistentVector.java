package moclj;

import java.lang.constant.ClassDesc;
import java.util.AbstractList;
import java.util.Arrays;

/// An immutable indexed collection, the core of `clojure.lang.PersistentVector`.
///
/// The elements live in the leaves of a tree whose nodes are arrays of 32, so an
/// index is routed by cutting its bits into groups of five: no division and no
/// hashing, just a shift and a mask per level. Thirty-two branches keep the tree
/// flat enough - four levels hold 32^4 = 1,048,576 elements - that lookup is
/// `O(log32 n)`, effectively constant.
///
/// Adding or replacing an element copies only the nodes on the path from the
/// root down to the leaf it touches, at most one array of 32 per level, and the
/// new version points at the old version's other subtrees. That is structural
/// sharing: a million-element vector and the vector one element longer than it
/// exist at the same time while sharing all but a few dozen pointers.
///
/// Real Clojure additionally keeps the last, incomplete leaf in a `tail` array
/// so that appending usually touches no tree node at all; the tree below is the
/// part that makes the collection persistent.
public final class PersistentVector extends AbstractList<Object> {

    static final ClassDesc CLASS_DESC = ClassDesc.of(PersistentVector.class.getName());

    /// Five bits per level, hence 32 branches per node: an array of 32 pointers
    /// is a couple of cache lines, and the arithmetic is shift-and-mask.
    private static final int BITS = 5;
    private static final int BRANCHING_FACTOR = 1 << BITS;
    private static final int BIT_MASK = BRANCHING_FACTOR - 1;

    public static final PersistentVector EMPTY = new PersistentVector(0, 0, Node.empty());

    /// One tree node: 32 slots holding either child nodes or, in a leaf,
    /// elements. Never mutated after it is handed out.
    record Node(Object[] array) {

        static Node empty() {
            return new Node(new Object[BRANCHING_FACTOR]);
        }

        /// The same node with one slot replaced, as a new node.
        Node set(int index, Object value) {
            Object[] copy = Arrays.copyOf(array, array.length);
            copy[index] = value;
            return new Node(copy);
        }
    }

    private final int count;

    /// Bits to shift an index by at the root, so `shift / BITS + 1` levels.
    private final int shift;

    private final Node root;

    private PersistentVector(int count, int shift, Node root) {
        this.count = count;
        this.shift = shift;
        this.root = root;
    }

    /// The vector holding `items` in order.
    public static PersistentVector of(Object... items) {
        PersistentVector vector = EMPTY;
        for (Object item : items) {
            vector = vector.conj(item);
        }
        return vector;
    }

    /// The element at `index`, reached by masking five bits of the index per
    /// level on the way down.
    public Object nth(int index) {
        checkBounds(index);
        Node node = root;
        for (int level = shift; level > 0; level -= BITS) {
            node = (Node) node.array()[(index >>> level) & BIT_MASK];
        }
        return node.array()[index & BIT_MASK];
    }

    /// This vector with `value` appended, sharing every subtree the new element
    /// does not land in.
    public PersistentVector conj(Object value) {
        if (count == 1 << (shift + BITS)) {
            // The tree is full: make the current root the leftmost child of a
            // new root, which adds a level and multiplies the capacity by 32.
            Node grown = Node.empty().set(0, root);
            return new PersistentVector(count, shift + BITS, grown).conj(value);
        }
        return new PersistentVector(count + 1, shift, put(shift, root, count, value));
    }

    /// This vector with `index` replaced by `value`; `index == count` appends,
    /// as Clojure's `assoc` does.
    public PersistentVector assoc(int index, Object value) {
        if (index == count) {
            return conj(value);
        }
        checkBounds(index);
        return new PersistentVector(count, shift, put(shift, root, index, value));
    }

    /// Path copying: rebuild the nodes from `node` down to the leaf owning
    /// `index`, leaving the nodes off that path shared with the old version.
    private static Node put(int level, Node node, int index, Object value) {
        int slot = (index >>> level) & BIT_MASK;
        if (level == 0) {
            return node.set(slot, value);
        }
        Node child = (Node) node.array()[slot];
        return node.set(slot, put(level - BITS, child == null ? Node.empty() : child, index, value));
    }

    private void checkBounds(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }
    }

    /// The root, for tests asserting which nodes two versions share.
    Node root() {
        return root;
    }

    @Override
    public Object get(int index) {
        return nth(index);
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(Repl.print(nth(i)));
        }
        return sb.append(']').toString();
    }
}
