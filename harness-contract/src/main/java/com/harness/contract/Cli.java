package com.harness.contract;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 命令行入口（scripts/harness/contract-cli.sh 调用）：
 * <pre>
 *   lint [spec...]        契约规范检查
 *   diff base.yaml head   破坏性变更检测 + 前端变更日志
 *   migration-lint dir [--new a.sql,b.sql]   数据库迁移检查
 *   report [metrics.jsonl] [--html out.html] harness 质量报告
 * </pre>
 */
public final class Cli {

    public static void main(String[] args) throws Exception {
        // 与终端 locale 无关地输出 UTF-8（中文提示不乱码）
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        String cmd = args.length == 0 ? "" : args[0];
        String[] rest = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        switch (cmd) {
            case "lint" -> SpecLint.main(rest);
            case "diff" -> SpecDiff.main(rest);
            case "migration-lint" -> MigrationLint.main(rest);
            case "report" -> Report.main(rest);
            default -> {
                System.err.println("usage: contract-cli (lint [spec...] | diff <base.yaml> <head.yaml> | migration-lint <dir> [--new a.sql,b.sql] | report [metrics.jsonl] [--html out.html])");
                System.exit(2);
            }
        }
    }

    private Cli() {
    }
}
