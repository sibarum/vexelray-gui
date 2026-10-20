package dev.vexelray.gui.nfd;

import dev.vexelray.gui.nfd.spi.NfdPlatform;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;

/**
 * The one {@link NfdPlatform} on the class path, and the only place this module asks which platform it is on. It
 * asks no {@code os.name}: the consumer's OS-activated build profile put exactly one {@code vexelray-gui-nfd-<os>}
 * module beside this one, and that module answers.
 */
final class Platform {

    private Platform() {
    }

    private static final class Found {
        static final NfdPlatform INSTANCE = choose(ServiceLoader.load(NfdPlatform.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    }

    /**
     * The platform's answers. Throws {@link UnsatisfiedLinkError} when there is no OS module, which every caller
     * already treats as "the native library is not there": a failed dialog, not a failed application.
     */
    static NfdPlatform current() {
        return Found.INSTANCE;
    }

    /** The one platform among {@code found}. Separate from {@link #current()} so a missing module can be tested. */
    static NfdPlatform choose(List<NfdPlatform> found) {
        if (found.size() != 1) {
            throw new UnsatisfiedLinkError((found.isEmpty() ? "no" : found.size() + "")
                    + " vexelray-gui-nfd OS modules on the class path; exactly one of vexelray-gui-nfd-windows, "
                    + "-macos or -linux is needed, which vexelray-gui-nfd's OS-activated build profile adds");
        }
        return found.getFirst();
    }

    /** Where a dialog runs on {@code platform}, given the way onto the window's thread. */
    static Executor lane(NfdPlatform platform, Executor guiThread) {
        return platform.lane() == NfdPlatform.Lane.GUI_THREAD ? guiThread : DialogThread.shared();
    }
}
