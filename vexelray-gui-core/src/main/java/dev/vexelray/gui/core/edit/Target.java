package dev.vexelray.gui.core.edit;

import dev.vexelray.gui.core.nav.Address;

import java.util.Objects;

/**
 * What a {@link Change} is about: the place the user is taken before it is undone or redone, so that nothing
 * changes where they cannot see it (docs/plans/undo.md §2).
 *
 * <p><b>Two forms, because a node does not always outlive its edit.</b> A widget that stays put — a toggle in a
 * panel built once — is named by its node id. A widget that is rebuilt from its model — an inspector row, a list
 * item — gets a new node on every rebuild, so an id recorded before the rebuild names nothing afterwards; it is
 * named by an {@link Address} instead, which the rebuilt node re-registers. A node-targeted entry whose node is
 * gone is not an error in the history: the undo fails as unreachable, and says so.
 *
 * @param node    the node's id, or {@link #NO_NODE} when the target is an address
 * @param address the landmark to navigate to, or null when the target is a node
 */
public record Target(long node, Address address) {

    /** The node id meaning "not a node". Node ids are allocated from 1. */
    public static final long NO_NODE = 0;

    public Target {
        if ((node == NO_NODE) == (address == null)) {
            throw new IllegalArgumentException("a target is a node or an address, exactly one: " + node + ", "
                    + address);
        }
    }

    /** The node with this id. */
    public static Target node(long id) {
        if (id == NO_NODE) {
            throw new IllegalArgumentException("not a node id: " + id);
        }
        return new Target(id, null);
    }

    /** Whatever node holds this landmark when the undo comes. */
    public static Target address(Address address) {
        return new Target(NO_NODE, Objects.requireNonNull(address, "address"));
    }

    /** Whether this names a node directly rather than through an address. */
    public boolean isNode() {
        return address == null;
    }
}
