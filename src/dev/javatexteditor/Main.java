package dev.javatexteditor;

import dev.javatexteditor.app.EditorApplication;

/**
 * エントリポイント。組み立て（起動引数の解析・サービス生成・GUI構築）は
 * すべて {@link EditorApplication} が担う（MAIN_DECOMPOSITION_PLAN.md 段階7、
 * docs/STAGE7_PLAN.md 7-2）。
 */
public class Main {

    public static void main(String[] args) {
        EditorApplication.launch(args);
    }
}
