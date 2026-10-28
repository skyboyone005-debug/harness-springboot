package com.harness.contract;

import java.util.Arrays;

/**
 * 命令行入口（scripts/harness/contract-cli.sh 调用）：
 * <pre>
 *   lint [spec...]        契约规范检查
 *   diff base.yaml head   破坏性变更检测 + 前端变更日志
 * </pre>
 */
public final class Cli {

    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "" : args[0];
        String[] rest = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        switch (cmd) {
            case "lint" -> SpecLint.main(rest);
            case "diff" -> SpecDiff.main(rest);
            default -> {
                System.err.println("usage: contract-cli (lint [spec...] | diff <base.yaml> <head.yaml>)");
                System.exit(2);
            }
        }
    }

    private Cli() {
    }
}
