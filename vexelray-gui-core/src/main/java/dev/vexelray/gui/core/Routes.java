package dev.vexelray.gui.core;

import sibarum.atchung.Inbox;
import sibarum.atchung.Subscriber;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;
import sibarum.probe.Lane;
import sibarum.probe.Probe;

import java.util.HashMap;
import java.util.Map;

/**
 * The widgets' addressed mailboxes, one route table per topic: a message on the topic is handed to whichever mailbox
 * answers to its {@link Addressed#address()}. One subscription per topic in the GUI's widget inbox, however many
 * widgets listen on it, so routing a message costs a map lookup rather than a call per widget.
 *
 * <p>The GUI thread's alone: routes are added and removed by widgets, which are built and closed there, and messages
 * are delivered there, in the widget tick.
 */
final class Routes {

    /** How many edges one topic may hold for every widget on it between two frames. */
    private static final int CAPACITY = 4096;

    private final Inbox inbox;
    private final Map<Topic<?>, Table<?>> tables = new HashMap<>();

    Routes(Inbox inbox) {
        this.inbox = inbox;
    }

    <T extends Addressed> Subscription add(Topic<T> topic, Object address, Subscriber<T> stage) {
        @SuppressWarnings("unchecked")
        Table<T> table = (Table<T>) tables.computeIfAbsent(topic, t -> new Table<>(topic, inbox));
        return table.add(address, stage);
    }

    /** One topic's routes, and the one subscription that feeds them. */
    private static final class Table<T extends Addressed> {
        private final Topic<T> topic;
        private final Map<Object, Subscriber<T>> routes = new HashMap<>();

        Table(Topic<T> topic, Inbox inbox) {
            this.topic = topic;
            inbox.subscribe(topic, this::route, CAPACITY);
        }

        private void route(T message) {
            Subscriber<T> stage = routes.get(message.address());
            if (stage != null) {
                stage.on(message);
            } else {
                // For a widget that has gone, or was never there: the shape of "the command did nothing", counted so
                // it is a row in a profile rather than a mystery.
                Probe.count(Lane.BUS, topic.name() + " unrouted");
            }
        }

        Subscription add(Object address, Subscriber<T> stage) {
            if (routes.putIfAbsent(address, stage) != null) {
                throw new IllegalStateException("two mailboxes answer to '" + address + "' on " + topic.name()
                        + ": an address is one widget's");
            }
            return new Subscription() {
                private boolean active = true;
                private boolean paused;

                @Override
                public boolean isActive() {
                    return active;
                }

                @Override
                public void pause() {
                    paused = true;
                    routes.remove(address, stage);
                }

                @Override
                public void resume() {
                    if (active && paused) {
                        paused = false;
                        routes.putIfAbsent(address, stage);
                    }
                }

                @Override
                public boolean isPaused() {
                    return paused;
                }

                @Override
                public void close() {
                    active = false;
                    routes.remove(address, stage);
                }
            };
        }
    }
}
