@echo off
rem ottermate, as a command rather than as a path to a jar.
rem
rem Every example in docs\automation-cli.md is written `ottermate shot after.png`, and until this existed the
rem only way to run it was to type the jar's full path with its version in it -- so the tool that was built to
rem fix a reachability defect had one of its own. Put this module's directory on PATH once and the docs become
rem runnable as written.
rem
rem Nothing here is a launcher in the wrapper-script sense: no classpath assembly, no JAVA_OPTS, no config
rem file. The jar is executable on its own (an empty dependency block is what buys that, pom.xml), so this
rem finds it, runs it, and gets out of the way.
setlocal

set "HERE=%~dp0"

rem Globbed rather than named: the version is the parent pom's and moves. /o-d is newest first, so a rebuild
rem under a bumped version wins over the stale jar beside it rather than being picked by lexical accident. The
rem findstr filter is for the -sources and -javadoc jars a release build leaves in the same directory; neither
rem has a manifest to run, and picking one gives "no main manifest attribute", which reads as a broken tool.
set "JAR="
for /f "delims=" %%j in ('dir /b /a-d /o-d "%HERE%target\vexelray-gui-automation-cli-*.jar" 2^>nul ^| findstr /v /i "sources javadoc"') do if not defined JAR set "JAR=%HERE%target\%%j"

if not defined JAR (
    rem 127 rather than one of the tool's own five statuses (0 ok, 1 err, 2 usage, 3 nothing to drive, 4 it
    rem went away). None of them fits -- the socket was never reached to have an opinion -- and 127 is the
    rem shell's own "not installed", which is exactly what happened.
    echo ottermate: not built. From the vexelray-gui root:>&2
    echo     mvn -pl vexelray-gui-automation-cli package>&2
    echo No -am: this module depends on nothing, so the rest of the reactor is not involved.>&2
    exit /b 127
)

rem The status *is* the verdict (docs\automation-cli.md section 10), so it is passed through rather than replaced: a
rem wrapper that returned its own would be a script silently deciding that every run succeeded.
java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
