package dev.javatexteditor.classfile;

/**
 * JVM標準命令セット（The Java Virtual Machine Specification, Chapter 6）のニーモニック名テーブル。
 * opcode 0〜201が標準命令。202(breakpoint)/254(impdep1)/255(impdep2)はJVM実装内部専用の予約命令、
 * それ以外の未使用領域は null のままにする（{@link #mnemonic(int)} が null を返す）。
 */
public final class Opcodes {

    private static final String[] MNEMONICS = new String[256];

    static {
        set(0, "nop"); set(1, "aconst_null");
        set(2, "iconst_m1"); set(3, "iconst_0"); set(4, "iconst_1"); set(5, "iconst_2");
        set(6, "iconst_3"); set(7, "iconst_4"); set(8, "iconst_5");
        set(9, "lconst_0"); set(10, "lconst_1");
        set(11, "fconst_0"); set(12, "fconst_1"); set(13, "fconst_2");
        set(14, "dconst_0"); set(15, "dconst_1");
        set(16, "bipush"); set(17, "sipush");
        set(18, "ldc"); set(19, "ldc_w"); set(20, "ldc2_w");
        set(21, "iload"); set(22, "lload"); set(23, "fload"); set(24, "dload"); set(25, "aload");
        set(26, "iload_0"); set(27, "iload_1"); set(28, "iload_2"); set(29, "iload_3");
        set(30, "lload_0"); set(31, "lload_1"); set(32, "lload_2"); set(33, "lload_3");
        set(34, "fload_0"); set(35, "fload_1"); set(36, "fload_2"); set(37, "fload_3");
        set(38, "dload_0"); set(39, "dload_1"); set(40, "dload_2"); set(41, "dload_3");
        set(42, "aload_0"); set(43, "aload_1"); set(44, "aload_2"); set(45, "aload_3");
        set(46, "iaload"); set(47, "laload"); set(48, "faload"); set(49, "daload");
        set(50, "aaload"); set(51, "baload"); set(52, "caload"); set(53, "saload");
        set(54, "istore"); set(55, "lstore"); set(56, "fstore"); set(57, "dstore"); set(58, "astore");
        set(59, "istore_0"); set(60, "istore_1"); set(61, "istore_2"); set(62, "istore_3");
        set(63, "lstore_0"); set(64, "lstore_1"); set(65, "lstore_2"); set(66, "lstore_3");
        set(67, "fstore_0"); set(68, "fstore_1"); set(69, "fstore_2"); set(70, "fstore_3");
        set(71, "dstore_0"); set(72, "dstore_1"); set(73, "dstore_2"); set(74, "dstore_3");
        set(75, "astore_0"); set(76, "astore_1"); set(77, "astore_2"); set(78, "astore_3");
        set(79, "iastore"); set(80, "lastore"); set(81, "fastore"); set(82, "dastore");
        set(83, "aastore"); set(84, "bastore"); set(85, "castore"); set(86, "sastore");
        set(87, "pop"); set(88, "pop2");
        set(89, "dup"); set(90, "dup_x1"); set(91, "dup_x2");
        set(92, "dup2"); set(93, "dup2_x1"); set(94, "dup2_x2");
        set(95, "swap");
        set(96, "iadd"); set(97, "ladd"); set(98, "fadd"); set(99, "dadd");
        set(100, "isub"); set(101, "lsub"); set(102, "fsub"); set(103, "dsub");
        set(104, "imul"); set(105, "lmul"); set(106, "fmul"); set(107, "dmul");
        set(108, "idiv"); set(109, "ldiv"); set(110, "fdiv"); set(111, "ddiv");
        set(112, "irem"); set(113, "lrem"); set(114, "frem"); set(115, "drem");
        set(116, "ineg"); set(117, "lneg"); set(118, "fneg"); set(119, "dneg");
        set(120, "ishl"); set(121, "lshl"); set(122, "ishr"); set(123, "lshr");
        set(124, "iushr"); set(125, "lushr");
        set(126, "iand"); set(127, "land"); set(128, "ior"); set(129, "lor");
        set(130, "ixor"); set(131, "lxor");
        set(132, "iinc");
        set(133, "i2l"); set(134, "i2f"); set(135, "i2d");
        set(136, "l2i"); set(137, "l2f"); set(138, "l2d");
        set(139, "f2i"); set(140, "f2l"); set(141, "f2d");
        set(142, "d2i"); set(143, "d2l"); set(144, "d2f");
        set(145, "i2b"); set(146, "i2c"); set(147, "i2s");
        set(148, "lcmp"); set(149, "fcmpl"); set(150, "fcmpg");
        set(151, "dcmpl"); set(152, "dcmpg");
        set(153, "ifeq"); set(154, "ifne"); set(155, "iflt"); set(156, "ifge");
        set(157, "ifgt"); set(158, "ifle");
        set(159, "if_icmpeq"); set(160, "if_icmpne"); set(161, "if_icmplt");
        set(162, "if_icmpge"); set(163, "if_icmpgt"); set(164, "if_icmple");
        set(165, "if_acmpeq"); set(166, "if_acmpne");
        set(167, "goto"); set(168, "jsr"); set(169, "ret");
        set(170, "tableswitch"); set(171, "lookupswitch");
        set(172, "ireturn"); set(173, "lreturn"); set(174, "freturn");
        set(175, "dreturn"); set(176, "areturn"); set(177, "return");
        set(178, "getstatic"); set(179, "putstatic"); set(180, "getfield"); set(181, "putfield");
        set(182, "invokevirtual"); set(183, "invokespecial"); set(184, "invokestatic");
        set(185, "invokeinterface"); set(186, "invokedynamic");
        set(187, "new"); set(188, "newarray"); set(189, "anewarray");
        set(190, "arraylength"); set(191, "athrow");
        set(192, "checkcast"); set(193, "instanceof");
        set(194, "monitorenter"); set(195, "monitorexit");
        set(196, "wide"); set(197, "multianewarray");
        set(198, "ifnull"); set(199, "ifnonnull");
        set(200, "goto_w"); set(201, "jsr_w");
        set(202, "breakpoint");
        set(254, "impdep1"); set(255, "impdep2");
    }

    private Opcodes() {}

    private static void set(int code, String mnemonic) {
        MNEMONICS[code] = mnemonic;
    }

    /** 未定義（予約済み・未使用）のopcodeにはnullを返す。呼び出し側でunknown表示にフォールバックする。 */
    public static String mnemonic(int opcode) {
        return MNEMONICS[opcode & 0xFF];
    }
}
