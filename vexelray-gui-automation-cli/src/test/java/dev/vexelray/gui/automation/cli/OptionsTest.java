package dev.vexelray.gui.automation.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the command line means, decided without opening anything. */
class OptionsTest {

    @Test
    void trailingArgumentsAreOneCommand() {
        Options options = Options.parse(new String[]{"shot", "after.png"});
        assertEquals("shot after.png", options.command());
        assertEquals(AutomationClient.DEFAULT_PORT, options.port());
        assertFalse(options.launching());
    }

    @Test
    void aCommandWithSpacesSurvivesTheShellSplittingIt() {
        // `ottermate type hello world` is one command with an argument of two words, not two commands. Rejoining
        // is the only reading that does not need the caller to quote what the shell already unquoted.
        assertEquals("type hello world", Options.parse(new String[]{"type", "hello", "world"}).command());
    }

    @Test
    void noArgumentsReadsStdin() {
        Options options = Options.parse(new String[0]);
        assertTrue(options.readingStdin());
        assertNull(options.command());
        assertNull(options.script());
    }

    @Test
    void dashScriptIsStdinToo() {
        assertTrue(Options.parse(new String[]{"--script", "-"}).readingStdin());
    }

    @Test
    void aScriptFileIsNotStdin() {
        Options options = Options.parse(new String[]{"--script", "panels.txt"});
        assertEquals(Path.of("panels.txt"), options.script());
        assertFalse(options.readingStdin());
    }

    @Test
    void launchTakesTheWholeRestOfTheLine() {
        Options options = Options.parse(new String[]{
                "--quiet", "--launch", "mvn", "-pl", "app", "exec:exec", "-Dautomation=0"});
        assertTrue(options.quiet());
        assertEquals(List.of("mvn", "-pl", "app", "exec:exec", "-Dautomation=0"), options.launch());
        assertNull(options.command(), "nothing after --launch is this tool's, including things that look "
                + "like commands");
    }

    @Test
    void portAndLaunchAreRefusedTogether() {
        UsageException e = assertThrows(UsageException.class,
                () -> Options.parse(new String[]{"--port", "7654", "--launch", "app.exe", "--automation=0"}));
        assertTrue(e.getMessage().contains("--automation"),
                "the message has to say where the port does belong: " + e.getMessage());
    }

    @Test
    void aScriptAndACommandAreTwoSourcesAndRefused() {
        assertThrows(UsageException.class,
                () -> Options.parse(new String[]{"--script", "panels.txt", "shot", "after.png"}));
    }

    @Test
    void anOptionWithoutItsValueIsRefusedRatherThanDefaulted() {
        assertThrows(UsageException.class, () -> Options.parse(new String[]{"--port"}));
        assertThrows(UsageException.class, () -> Options.parse(new String[]{"--launch"}));
    }

    @Test
    void aPortThatIsNotANumberIsRefused() {
        UsageException e = assertThrows(UsageException.class,
                () -> Options.parse(new String[]{"--port", "localhost:7654"}));
        assertTrue(e.getMessage().contains("localhost:7654"), e.getMessage());
    }

    @Test
    void anUnknownOptionIsRefusedRatherThanTakenForACommand() {
        // Otherwise `ottermate --potr 7654 shot x.png` attaches to the default port and photographs the wrong
        // window, which is a wrong answer that looks like a working run.
        assertThrows(UsageException.class, () -> Options.parse(new String[]{"--potr", "7654"}));
    }

    @Test
    void helpBeatsEveryContradictionOnTheLine() {
        assertTrue(Options.parse(new String[]{"--help"}).help());
        assertTrue(Options.parse(new String[]{"--script", "a.txt", "shot", "--help"}).help(),
                "somebody asking what the options are has not got them right yet");
    }
}
