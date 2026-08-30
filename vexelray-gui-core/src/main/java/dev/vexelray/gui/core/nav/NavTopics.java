package dev.vexelray.gui.core.nav;

import sibarum.atchung.Topic;

/**
 * The navigation channel: an {@link Address} published here is carried out by whichever window owns the
 * landmark, opening and raising that window first if it is closed
 * ({@code GuiApp} subscribes on behalf of every window it knows by name).
 *
 * <p>Being a topic rather than a method call is the whole of cross-window navigation, and most of macros and
 * automation with it: the sender needs no handle on the destination, needs not be on the GUI thread, and needs
 * not even be in this process. A hyperlink in a document, a step in a recorded macro and a test driving the
 * application all publish the same value.
 */
public final class NavTopics {

    /** The channel name a navigation request is published on. */
    public static final String GO_NAME = "vexelray.gui.navigate";

    /** Requests to navigate somewhere. */
    public static final Topic<Address> GO = Topic.of(GO_NAME, Address.class);

    private NavTopics() {
    }
}
