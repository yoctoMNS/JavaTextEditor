package dev.javatexteditor.editor;

import java.util.List;

/**
 * ModalEditor.applyCIncludes（C の #include 自動挿入をバッファへ反映する編集操作）を検証する。
 * サブプロセス非依存（CIncludeManager の純粋ロジックを buffer.insert へ橋渡しするだけ）。
 */
public class CIncludeApplyTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testAppliesMissingHeader();
        testInsertsAfterExistingInclude();
        testSkipsAlreadyIncluded();
        testEmptyListNoChange();
        testReturnsAddedCount();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean cond) {
        if (cond) { System.out.println("  PASS: " + name); pass++; }
        else { System.out.println("  FAIL: " + name); fail++; }
    }

    static void testAppliesMissingHeader() {
        ModalEditor ed = new ModalEditor("int main(void){ return 0; }\n");
        ed.applyCIncludes(List.of("stdio.h"));
        check("先頭に #include <stdio.h> が挿入される",
            ed.getText().startsWith("#include <stdio.h>\n"));
    }

    static void testInsertsAfterExistingInclude() {
        ModalEditor ed = new ModalEditor("#include <stdio.h>\nint main(void){ return 0; }\n");
        ed.applyCIncludes(List.of("stdlib.h"));
        check("既存 include の直後に挿入される",
            ed.getText().equals("#include <stdio.h>\n#include <stdlib.h>\nint main(void){ return 0; }\n"));
    }

    static void testSkipsAlreadyIncluded() {
        ModalEditor ed = new ModalEditor("#include <stdio.h>\nint main(void){ return 0; }\n");
        int n = ed.applyCIncludes(List.of("stdio.h"));
        check("既に include 済みなら追加しない (count=0)", n == 0);
        check("バッファも変わらない",
            ed.getText().equals("#include <stdio.h>\nint main(void){ return 0; }\n"));
    }

    static void testEmptyListNoChange() {
        ModalEditor ed = new ModalEditor("int main(void){ return 0; }\n");
        String before = ed.getText();
        int n = ed.applyCIncludes(List.of());
        check("空リストは無変更", n == 0 && ed.getText().equals(before));
    }

    static void testReturnsAddedCount() {
        ModalEditor ed = new ModalEditor("int main(void){ return 0; }\n");
        int n = ed.applyCIncludes(List.of("stdio.h", "stdlib.h"));
        check("追加件数を返す (2)", n == 2);
    }
}
