package com.harness.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * migration-lint：把"数据库变更要可回滚、可在线执行"写成代码。
 *
 * <pre>
 *   migration-lint &lt;migration-dir&gt; [--new V2__x.sql,V3__y.sql]
 * </pre>
 *
 * 规则（违反即失败，Agent 会在 PostToolUse / Stop 阶段收到具体行号）：
 * <ol>
 *   <li>文件名必须是 {@code V<版本>__<小写下划线描述>.sql}，版本号不得重复；</li>
 *   <li>新增迁移的版本号必须大于所有已存在迁移（禁止插队，否则已上线环境不会执行它）；</li>
 *   <li>破坏性语句（DROP TABLE/COLUMN、TRUNCATE、RENAME、MODIFY/CHANGE 列类型、无 WHERE 的 DELETE/UPDATE）默认拒绝，
 *       确需执行时在语句前一行写 {@code -- harness:allow-destructive <原因>}，让人工审查有据可查；</li>
 *   <li>{@code ADD COLUMN ... NOT NULL} 必须带 DEFAULT，否则已有数据的表会迁移失败。</li>
 * </ol>
 * "已提交的迁移不可修改"由 scripts/harness 基于 git 判断（需要知道 BASE），不在这里。
 */
public final class MigrationLint {

    static final Pattern NAME = Pattern.compile("^V(\\d+)__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql$");
    static final String ALLOW = "harness:allow-destructive";

    private record Rule(Pattern pattern, String why) {
    }

    private static final List<Rule> DESTRUCTIVE = List.of(
            new Rule(Pattern.compile("^DROP\\s+(TABLE|DATABASE|SCHEMA|VIEW)\\b"), "drops an object and its data"),
            new Rule(Pattern.compile("^ALTER\\s+TABLE\\s+\\S+\\s+.*\\bDROP\\s+(COLUMN\\b|(?!INDEX|KEY|CONSTRAINT|FOREIGN|PRIMARY)\\w+)"), "drops a column; old app versions still read it"),
            new Rule(Pattern.compile("^TRUNCATE\\b"), "deletes all rows"),
            new Rule(Pattern.compile("^(RENAME\\s+TABLE\\b|ALTER\\s+TABLE\\s+\\S+\\s+.*\\bRENAME\\b)"), "renames break running app versions; add new + backfill + drop later"),
            new Rule(Pattern.compile("^ALTER\\s+TABLE\\s+\\S+\\s+.*\\b(MODIFY|CHANGE)\\b"), "changes a column definition in place (type narrowing / table rebuild)"),
            new Rule(Pattern.compile("^(DELETE\\s+FROM|UPDATE)\\s+(?!.*\\bWHERE\\b)"), "touches every row (no WHERE)"));

    private static final Pattern ADD_NOT_NULL = Pattern.compile("\\bADD\\s+(COLUMN\\s+)?\\S+\\s+[^,]*\\bNOT\\s+NULL\\b");

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: migration-lint <migration-dir> [--new file1,file2]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        Set<String> added = args.length >= 3 && "--new".equals(args[1]) ? Set.of(args[2].split(",")) : Set.of();
        List<String> problems = lint(dir, added);
        problems.forEach(System.err::println);
        if (!problems.isEmpty()) {
            System.err.println("migration-lint: " + problems.size() + " problem(s)");
            System.exit(1);
        }
        System.out.println("migration-lint: " + dir + " OK");
    }

    /** @param added 本次新增（BASE 中不存在）的文件名；用于"禁止插队"检查。 */
    public static List<String> lint(Path dir, Set<String> added) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
        long maxExisting = 0;
        List<Long> seen = new ArrayList<>();
        for (Path f : files) {
            Matcher m = NAME.matcher(f.getFileName().toString());
            if (!m.matches()) {
                out.add(f + ": file name must be V<version>__<snake_case_description>.sql");
                continue;
            }
            long v = Long.parseLong(m.group(1));
            if (seen.contains(v)) {
                out.add(f + ": duplicate version V" + v);
            }
            seen.add(v);
            if (!added.contains(f.getFileName().toString())) {
                maxExisting = Math.max(maxExisting, v);
            }
        }
        for (Path f : files) {
            String name = f.getFileName().toString();
            Matcher m = NAME.matcher(name);
            if (m.matches() && added.contains(name) && Long.parseLong(m.group(1)) <= maxExisting) {
                out.add(f + ": new migration V" + m.group(1) + " must be > V" + maxExisting
                        + " (environments that already ran V" + maxExisting + " would skip it)");
            }
            out.addAll(lintSql(f.toString(), Files.readString(f)));
        }
        return out;
    }

    /** 逐条语句检查；行号指向语句开头，便于 Agent 定位。 */
    public static List<String> lintSql(String file, String sql) {
        List<String> out = new ArrayList<>();
        String[] lines = sql.split("\n", -1);
        StringBuilder stmt = new StringBuilder();
        int startLine = 0;
        boolean allowed = false;
        String pendingAllow = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                if (trimmed.contains(ALLOW)) {
                    String reason = trimmed.substring(trimmed.indexOf(ALLOW) + ALLOW.length()).trim();
                    if (reason.isEmpty()) {
                        out.add(file + ":" + (i + 1) + ": '" + ALLOW + "' needs a reason after it");
                    } else {
                        pendingAllow = reason;
                    }
                }
                continue;
            }
            if (stmt.length() == 0 && !trimmed.isEmpty()) {
                startLine = i + 1;
                allowed = pendingAllow != null;
                pendingAllow = null;
            }
            String code = line.contains("--") ? line.substring(0, line.indexOf("--")) : line;
            stmt.append(code).append(' ');
            if (code.contains(";")) {
                check(file, startLine, stmt.toString(), allowed, out);
                stmt.setLength(0);
            }
        }
        if (!stmt.toString().isBlank()) {
            check(file, startLine, stmt.toString(), allowed, out);
        }
        return out;
    }

    private static void check(String file, int line, String raw, boolean allowed, List<String> out) {
        String s = raw.replace(";", " ").replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            return;
        }
        String where = file + ":" + line + ": ";
        if (!allowed) {
            for (Rule r : DESTRUCTIVE) {
                if (r.pattern().matcher(s).find()) {
                    out.add(where + "destructive statement (" + r.why() + "): " + abbreviate(s)
                            + " -> make it backward compatible, or precede it with '-- " + ALLOW + " <reason>'");
                    break;
                }
            }
        }
        if (s.startsWith("ALTER TABLE") && ADD_NOT_NULL.matcher(s).find() && !s.contains(" DEFAULT ")) {
            out.add(where + "ADD COLUMN ... NOT NULL without DEFAULT fails on tables that already have rows: " + abbreviate(s));
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    private MigrationLint() {
    }
}
