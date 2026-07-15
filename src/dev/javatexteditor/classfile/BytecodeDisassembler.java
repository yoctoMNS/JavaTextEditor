package dev.javatexteditor.classfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Code属性のバイトコード（{@link CodeAttribute#code()}）をjavap -c風のニーモニック命令列に変換する。
 * 分岐オペランドはメソッド内の絶対バイトオフセットに解決し、定数プール参照はコメント（"// ..."）を付与する。
 */
public final class BytecodeDisassembler {

    public record Instruction(int offset, String text) {}

    private BytecodeDisassembler() {}

    public static List<Instruction> disassemble(byte[] code, ClassFile classFile) {
        List<Instruction> result = new ArrayList<>();
        int pc = 0;
        while (pc < code.length) {
            int start = pc;
            int opcode = code[pc] & 0xFF;
            String mnemonic = Opcodes.mnemonic(opcode);
            StringBuilder line = new StringBuilder();
            if (mnemonic == null) {
                line.append("unknown_0x").append(Integer.toHexString(opcode));
                pc++;
            } else {
                line.append(mnemonic);
                pc++;
                pc = appendOperands(code, pc, opcode, start, line, classFile);
            }
            result.add(new Instruction(start, line.toString()));
        }
        return result;
    }

    private static int appendOperands(byte[] code, int pc, int opcode, int start, StringBuilder line, ClassFile cf) {
        switch (opcode) {
            case 16 -> { // bipush（符号付き1バイト）
                line.append(' ').append(code[pc]);
                pc += 1;
            }
            case 17 -> { // sipush（符号付き2バイト）
                line.append(' ').append(readS2(code, pc));
                pc += 2;
            }
            case 18 -> { // ldc（定数プールインデックスu1）
                int idx = code[pc] & 0xFF;
                line.append(" #").append(idx).append(cpComment(cf, idx));
                pc += 1;
            }
            case 19, 20 -> { // ldc_w, ldc2_w（定数プールインデックスu2）
                int idx = readU2(code, pc);
                line.append(" #").append(idx).append(cpComment(cf, idx));
                pc += 2;
            }
            case 21, 22, 23, 24, 25, // iload/lload/fload/dload/aload
                 54, 55, 56, 57, 58 -> { // istore/lstore/fstore/dstore/astore
                int idx = code[pc] & 0xFF;
                line.append(' ').append(idx);
                pc += 1;
            }
            case 169 -> { // ret
                int idx = code[pc] & 0xFF;
                line.append(' ').append(idx);
                pc += 1;
            }
            case 132 -> { // iinc（局所変数インデックスu1 + 符号付き定数s1）
                int idx = code[pc] & 0xFF;
                int c = code[pc + 1];
                line.append(' ').append(idx).append(", ").append(c);
                pc += 2;
            }
            case 153, 154, 155, 156, 157, 158, // ifeq..ifle
                 159, 160, 161, 162, 163, 164, 165, 166, // if_icmp*/if_acmp*
                 167, 168, // goto, jsr
                 198, 199 -> { // ifnull, ifnonnull
                int off = readS2(code, pc);
                line.append(' ').append(start + off);
                pc += 2;
            }
            case 200, 201 -> { // goto_w, jsr_w
                int off = readS4(code, pc);
                line.append(' ').append(start + off);
                pc += 4;
            }
            case 178, 179, 180, 181, // getstatic/putstatic/getfield/putfield
                 182, 183, 184, // invokevirtual/invokespecial/invokestatic
                 187, 189, 192, 193 -> { // new/anewarray/checkcast/instanceof
                int idx = readU2(code, pc);
                line.append(" #").append(idx).append(cpComment(cf, idx));
                pc += 2;
            }
            case 185 -> { // invokeinterface（インデックスu2 + count(u1) + 予約0(u1)）
                int idx = readU2(code, pc);
                int count = code[pc + 2] & 0xFF;
                line.append(" #").append(idx).append(cpComment(cf, idx)).append(",  ").append(count);
                pc += 4;
            }
            case 186 -> { // invokedynamic（インデックスu2 + 予約0(u1) + 予約0(u1)）
                int idx = readU2(code, pc);
                line.append(" #").append(idx).append(cpComment(cf, idx));
                pc += 4;
            }
            case 188 -> { // newarray（プリミティブ型atype）
                int atype = code[pc] & 0xFF;
                line.append(' ').append(arrayTypeName(atype));
                pc += 1;
            }
            case 197 -> { // multianewarray（インデックスu2 + 次元数u1）
                int idx = readU2(code, pc);
                int dims = code[pc + 2] & 0xFF;
                line.append(" #").append(idx).append(cpComment(cf, idx)).append(",  ").append(dims);
                pc += 3;
            }
            case 170 -> pc = disassembleTableSwitch(code, pc, start, line);
            case 171 -> pc = disassembleLookupSwitch(code, pc, start, line);
            case 196 -> pc = disassembleWide(code, pc, line);
            default -> { /* オペランドなし命令はそのまま */ }
        }
        return pc;
    }

    private static int disassembleTableSwitch(byte[] code, int pc, int start, StringBuilder line) {
        while (pc % 4 != 0) pc++;
        int def = readS4(code, pc); pc += 4;
        int low = readS4(code, pc); pc += 4;
        int high = readS4(code, pc); pc += 4;
        line.append(" { // ").append(low).append(" to ").append(high);
        for (int i = low; i <= high; i++) {
            int offset = readS4(code, pc); pc += 4;
            line.append("\n            ").append(i).append(": ").append(start + offset);
        }
        line.append("\n            default: ").append(start + def).append("\n         }");
        return pc;
    }

    private static int disassembleLookupSwitch(byte[] code, int pc, int start, StringBuilder line) {
        while (pc % 4 != 0) pc++;
        int def = readS4(code, pc); pc += 4;
        int npairs = readS4(code, pc); pc += 4;
        line.append(" { // ").append(npairs);
        for (int i = 0; i < npairs; i++) {
            int match = readS4(code, pc); pc += 4;
            int offset = readS4(code, pc); pc += 4;
            line.append("\n            ").append(match).append(": ").append(start + offset);
        }
        line.append("\n            default: ").append(start + def).append("\n         }");
        return pc;
    }

    private static int disassembleWide(byte[] code, int pc, StringBuilder line) {
        int modifiedOpcode = code[pc] & 0xFF;
        pc++;
        String modMnemonic = Opcodes.mnemonic(modifiedOpcode);
        if (modifiedOpcode == 132) { // iinc（wide版は局所変数インデックスu2 + 定数s2）
            int idx = readU2(code, pc); pc += 2;
            int c = readS2(code, pc); pc += 2;
            line.append(' ').append(modMnemonic).append(' ').append(idx).append(", ").append(c);
        } else {
            int idx = readU2(code, pc); pc += 2;
            line.append(' ').append(modMnemonic).append(' ').append(idx);
        }
        return pc;
    }

    private static String cpComment(ClassFile cf, int idx) {
        return "  // " + cf.describeConstant(idx);
    }

    private static String arrayTypeName(int atype) {
        return switch (atype) {
            case 4 -> "boolean";
            case 5 -> "char";
            case 6 -> "float";
            case 7 -> "double";
            case 8 -> "byte";
            case 9 -> "short";
            case 10 -> "int";
            case 11 -> "long";
            default -> "unknown(" + atype + ")";
        };
    }

    private static int readU2(byte[] code, int pc) {
        return ((code[pc] & 0xFF) << 8) | (code[pc + 1] & 0xFF);
    }

    private static int readS2(byte[] code, int pc) {
        return (short) readU2(code, pc);
    }

    private static int readS4(byte[] code, int pc) {
        return ((code[pc] & 0xFF) << 24) | ((code[pc + 1] & 0xFF) << 16)
                | ((code[pc + 2] & 0xFF) << 8) | (code[pc + 3] & 0xFF);
    }
}
