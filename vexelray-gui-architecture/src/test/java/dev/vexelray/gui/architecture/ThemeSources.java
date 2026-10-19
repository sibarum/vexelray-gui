package dev.vexelray.gui.architecture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The detector behind {@link RethemeGuardTest}: which classes settle a theme decision early and write the answer
 * onto a node, where a theme swapped later cannot reach it.
 *
 * <p>Three shapes, all exact in bytecode. A colour handed to a node's colour setter is the {@code Color} overload
 * being called — and since {@link PaletteGuardTest} already forbids a framework class minting a colour, any colour
 * that gets there came out of a theme. A lit or letterpress flag is a call to {@code Theme.lit()} or
 * {@code Theme.letterpress()} at all: the only thing a framework class could want the answer for is to write it
 * down, and {@code Finish.LIT} is that, resolved when drawn. And the first shape dressed as a paint:
 * {@code Paint.of(theme.color(role))} is the constant paint, the same in every theme, so it is the same mistake with
 * the right type — a peephole, because the colour is compiled immediately before the call that wraps it.
 *
 * <p>Separated from the test so the test can point it at a synthesized violation, for the reason
 * {@link ColourSources} gives.
 */
final class ThemeSources {

    static final String NODE = "dev/vexelray/gui/core/Node";
    static final String THEME = "dev/vexelray/gui/core/style/Theme";
    static final String PAINT = "dev/vexelray/gui/core/style/Paint";

    /** A node's colour setters, in the overload that takes a resolved colour. */
    private static final Set<String> COLOUR_SETTERS = Set.of(
            "background(L" + ColourSources.COLOR + ";)L" + NODE + ";",
            "textColor(L" + ColourSources.COLOR + ";)L" + NODE + ";",
            "border(Ldev/vexelray/gui/core/layout/Length;L" + ColourSources.COLOR + ";)L" + NODE + ";");

    /** The theme's two non-colour decisions. */
    private static final Set<String> FINISHES = Set.of("lit", "letterpress");

    private ThemeSources() {
    }

    /**
     * Every early-settled theme decision {@code classFile} writes, unless its top-level class is in {@code allowed}.
     * Returns {@code "owner.method() writes a resolved colour: Node.background"} or
     * {@code "owner.method() asks Theme.lit"} per violation.
     */
    static List<String> settledOutside(Set<String> allowed, byte[] classFile) {
        List<String> violations = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            private String self = "?";

            @Override
            public void visit(int version, int access, String name, String sig, String superName, String[] ifs) {
                self = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] ex) {
                if (allowed.contains(Bytecode.outer(self))) {
                    return null;
                }
                String where = self + "." + method;
                return new MethodVisitor(Opcodes.ASM9) {
                    /** Whether the instruction just before this one was a call to Theme.color. */
                    private boolean afterThemeColour;

                    @Override
                    public void visitMethodInsn(int op, String owner, String name, String d, boolean itf) {
                        if (NODE.equals(owner) && COLOUR_SETTERS.contains(name + d)) {
                            violations.add(where + "() writes a resolved colour: Node." + name);
                        } else if (THEME.equals(owner) && FINISHES.contains(name) && d.equals("()Z")) {
                            violations.add(where + "() asks Theme." + name);
                        } else if (afterThemeColour && PAINT.equals(owner) && name.equals("of")
                                && d.equals("(L" + ColourSources.COLOR + ";)L" + PAINT + ";")) {
                            violations.add(where + "() wraps a theme colour as a constant: Paint.of(theme.color(...))");
                        }
                        afterThemeColour = THEME.equals(owner) && name.equals("color");
                    }

                    // Any other instruction between the two breaks the peephole, as it should: the colour was then
                    // stored, passed somewhere else or worked on, and is not this exact shape.
                    @Override
                    public void visitInsn(int opcode) {
                        afterThemeColour = false;
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        afterThemeColour = false;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String fieldDesc) {
                        afterThemeColour = false;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return violations;
    }
}
