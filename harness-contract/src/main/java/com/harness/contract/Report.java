package com.harness.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * report：把 hooks 记录的 .harness/metrics.jsonl 汇总成"harness 是否有效"的证据。
 *
 * <pre>report [metrics.jsonl] [--html out.html]   # markdown 打到 stdout，--html 额外生成看板</pre>
 *
 * 指标口径（README 有解释）：
 * <ul>
 *   <li>Stop 一次通过率：Agent 会话里第一次 Stop 检查就通过的比例 —— 越高说明前面几层把问题挡得越早；</li>
 *   <li>平均修复轮数：Agent 会话在第一次通过前被 Stop 拦下的次数；</li>
 *   <li>早层拦截占比：L0 守卫 + L1 编辑后检查拦下的问题 / 全部拦截 —— 越高越便宜；</li>
 *   <li>升级到人工：Stop 连续拦截后放行交给人的次数。</li>
 * </ul>
 */
public final class Report {

    record Event(long ts, Map<String, String> f) {
        String get(String k) {
            return f.getOrDefault(k, "");
        }

        String event() {
            return get("event");
        }
    }

    record Layer(String id, String name, long count) {
    }

    record SessionRow(String session, String source, int stopChecks, int failsBeforePass, boolean passedFirst, boolean everPassed) {
    }

    static final Pattern FIELD = Pattern.compile("\"(\\w+)\":(\"((?:[^\"\\\\]|\\\\.)*)\"|(-?\\d+))");

    public static void main(String[] args) throws IOException {
        Path in = Path.of(".harness/metrics.jsonl");
        Path html = null;
        for (int i = 0; i < args.length; i++) {
            if ("--html".equals(args[i]) && i + 1 < args.length) {
                html = Path.of(args[++i]);
            } else {
                in = Path.of(args[i]);
            }
        }
        if (!Files.exists(in)) {
            System.err.println("report: " + in + " not found (hooks write it while you work; see README)");
            System.exit(1);
        }
        Report r = new Report(parse(Files.readAllLines(in)), in.toString());
        System.out.print(r.markdown());
        if (html != null) {
            Files.createDirectories(html.toAbsolutePath().getParent());
            Files.writeString(html, r.html());
            System.out.println("\nHTML dashboard: " + html);
        }
    }

    static List<Event> parse(List<String> lines) {
        List<Event> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, String> f = new LinkedHashMap<>();
            Matcher m = FIELD.matcher(line);
            while (m.find()) {
                f.put(m.group(1), m.group(3) != null ? m.group(3).replace("\\\"", "\"").replace("\\\\", "\\") : m.group(4));
            }
            if (f.containsKey("event")) {
                out.add(new Event(Long.parseLong(f.getOrDefault("ts", "0")), f));
            }
        }
        out.sort(Comparator.comparingLong(Event::ts));
        return out;
    }

    // ---------------------------------------------------------------- 计算

    final List<Event> events;
    final String source;

    Report(List<Event> events, String source) {
        this.events = events;
        this.source = source;
    }

    boolean isFail(Event e) {
        return "fail".equals(e.get("result"));
    }

    boolean isStopCheck(Event e) {
        return "verify".equals(e.event()) && "--hook".equals(e.get("mode"));
    }

    boolean isLateLayer(Event e) {
        return "verify".equals(e.event()) && (List.of("git", "ci").contains(e.get("source")) || "--all".equals(e.get("mode")));
    }

    List<Layer> layers() {
        long l0 = events.stream().filter(e -> "guard.block".equals(e.event())).count();
        long l1 = events.stream().filter(e -> "edit.check".equals(e.event()) && isFail(e)).count();
        long l2 = events.stream().filter(e -> "verify".equals(e.event()) && isFail(e) && !isLateLayer(e)).count();
        long l3 = events.stream().filter(e -> isLateLayer(e) && isFail(e)).count();
        return List.of(new Layer("L0", "执行前守卫", l0), new Layer("L1", "编辑后即时检查", l1),
                new Layer("L2", "结束前增量检查", l2), new Layer("L3", "提交 / CI", l3));
    }

    List<SessionRow> agentSessions() {
        Map<String, List<Event>> bySession = events.stream().filter(e -> "claude".equals(e.get("source")))
                .collect(Collectors.groupingBy(e -> e.get("session"), TreeMap::new, Collectors.toList()));
        List<SessionRow> rows = new ArrayList<>();
        bySession.forEach((s, evs) -> {
            List<Event> stops = evs.stream().filter(this::isStopCheck).toList();
            int fails = 0;
            boolean passed = false;
            for (Event e : stops) {
                if (isFail(e)) {
                    fails++;
                } else {
                    passed = true;
                    break;
                }
            }
            rows.add(new SessionRow(s, "claude", stops.size(), fails, !stops.isEmpty() && !isFail(stops.get(0)), passed));
        });
        return rows;
    }

    Map<String, Long> countBy(String event, Function<Event, String> key, boolean failsOnly) {
        Map<String, Long> m = events.stream().filter(e -> event.equals(e.event()) && (!failsOnly || isFail(e)))
                .collect(Collectors.groupingBy(key, Collectors.counting()));
        return m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    Map<String, Long> medianMs() {
        Map<String, List<Long>> ms = new TreeMap<>();
        for (Event e : events) {
            if (e.get("ms").isEmpty()) {
                continue;
            }
            String k = "edit.check".equals(e.event()) ? "L1 " + e.get("kind") : "verify".equals(e.event()) ? (isStopCheck(e) ? "L2 stop" : "L3/手动 verify") : null;
            if (k != null) {
                ms.computeIfAbsent(k, x -> new ArrayList<>()).add(Long.parseLong(e.get("ms")));
            }
        }
        Map<String, Long> out = new LinkedHashMap<>();
        ms.forEach((k, v) -> {
            List<Long> s = v.stream().sorted().toList();
            out.put(k, s.get(s.size() / 2));
        });
        return out;
    }

    record Kpis(int sessions, String firstPass, String avgLoops, long escalations, String earlyShare, long total) {
    }

    Kpis kpis() {
        List<SessionRow> withStop = agentSessions().stream().filter(r -> r.stopChecks() > 0).toList();
        long total = layers().stream().mapToLong(Layer::count).sum();
        long early = layers().get(0).count() + layers().get(1).count();
        return new Kpis(agentSessions().size(),
                withStop.isEmpty() ? "–" : pct(withStop.stream().filter(SessionRow::passedFirst).count(), withStop.size()),
                withStop.isEmpty() ? "–" : String.format(Locale.ROOT, "%.1f", withStop.stream().mapToInt(SessionRow::failsBeforePass).average().orElse(0)),
                events.stream().filter(e -> "verify.escalate".equals(e.event())).count(),
                total == 0 ? "–" : pct(early, total), total);
    }

    static String pct(long a, long b) {
        return Math.round(100.0 * a / b) + "%";
    }

    String range() {
        if (events.isEmpty()) {
            return "无数据";
        }
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
        return f.format(Instant.ofEpochMilli(events.get(0).ts())) + " ~ " + f.format(Instant.ofEpochMilli(events.get(events.size() - 1).ts()));
    }

    // ---------------------------------------------------------------- markdown

    String markdown() {
        Kpis k = kpis();
        StringBuilder md = new StringBuilder();
        md.append("# Harness 质量报告\n\n").append("数据：`").append(source).append("`，").append(events.size())
                .append(" 条事件，").append(range()).append("\n\n");
        md.append("| 指标 | 值 |\n|---|---|\n")
                .append("| Agent 会话数 | ").append(k.sessions()).append(" |\n")
                .append("| Stop 一次通过率 | ").append(k.firstPass()).append(" |\n")
                .append("| 平均修复轮数（首次通过前被拦次数） | ").append(k.avgLoops()).append(" |\n")
                .append("| 早层拦截占比（L0+L1 / 全部） | ").append(k.earlyShare()).append(" |\n")
                .append("| 升级到人工 | ").append(k.escalations()).append(" |\n\n");
        md.append("## 各层拦截\n\n| 层 | 名称 | 次数 |\n|---|---|---|\n");
        layers().forEach(l -> md.append("| ").append(l.id()).append(" | ").append(l.name()).append(" | ").append(l.count()).append(" |\n"));
        md.append("\n## 拦截原因 Top\n\n| 类别 | 原因 | 次数 |\n|---|---|---|\n");
        reasons().forEach(row -> md.append("| ").append(row[0]).append(" | ").append(row[1]).append(" | ").append(row[2]).append(" |\n"));
        md.append("\n## 检查耗时（中位数）\n\n| 检查 | ms |\n|---|---|\n");
        medianMs().forEach((n, v) -> md.append("| ").append(n).append(" | ").append(v).append(" |\n"));
        md.append("\n## Agent 会话\n\n| 会话 | Stop 检查次数 | 首次即通过 | 修复轮数 | 最终通过 |\n|---|---|---|---|---|\n");
        agentSessions().forEach(s -> md.append("| ").append(s.session()).append(" | ").append(s.stopChecks()).append(" | ")
                .append(s.stopChecks() == 0 ? "–" : s.passedFirst() ? "是" : "否").append(" | ").append(s.failsBeforePass())
                .append(" | ").append(s.stopChecks() == 0 ? "–" : s.everPassed() ? "是" : "否").append(" |\n"));
        return md.toString();
    }

    List<String[]> reasons() {
        List<String[]> rows = new ArrayList<>();
        countBy("guard.block", e -> e.get("rule"), false).forEach((r, n) -> rows.add(new String[]{"L0 守卫", r, String.valueOf(n)}));
        countBy("edit.check", e -> e.get("kind"), true).forEach((r, n) -> rows.add(new String[]{"L1 编辑后", r, String.valueOf(n)}));
        events.stream().filter(e -> "verify".equals(e.event()) && isFail(e))
                .collect(Collectors.groupingBy(e -> (isLateLayer(e) ? "L3 提交/CI" : "L2 verify") + "\u0000" + e.get("step"), Collectors.counting()))
                .forEach((k, n) -> rows.add(new String[]{k.split("\u0000")[0], k.split("\u0000")[1], String.valueOf(n)}));
        rows.sort(Comparator.comparingLong((String[] r) -> Long.parseLong(r[2])).reversed());
        return rows.size() > 10 ? rows.subList(0, 10) : rows;
    }

    // ---------------------------------------------------------------- html

    String html() {
        Kpis k = kpis();
        List<Layer> layers = layers();
        long max = Math.max(1, layers.stream().mapToLong(Layer::count).max().orElse(1));
        StringBuilder bars = new StringBuilder();
        for (Layer l : layers) {
            double w = 100.0 * l.count() / max;
            bars.append("<div class=\"bar-row\" title=\"").append(l.id()).append(' ').append(esc(l.name())).append("：")
                    .append(l.count()).append(" 次\"><div class=\"bar-label\"><b>").append(l.id()).append("</b> ")
                    .append(esc(l.name())).append("</div><div class=\"bar-track\"><div class=\"bar\" style=\"width:")
                    .append(String.format(Locale.ROOT, "%.1f", w)).append("%\"></div><span class=\"bar-value\">")
                    .append(l.count()).append("</span></div></div>\n");
        }
        StringBuilder reasons = new StringBuilder();
        reasons().forEach(r -> reasons.append("<tr><td>").append(esc(r[0])).append("</td><td><code>").append(esc(r[1]))
                .append("</code></td><td class=\"num\">").append(r[2]).append("</td></tr>\n"));
        StringBuilder timing = new StringBuilder();
        medianMs().forEach((n, v) -> timing.append("<tr><td>").append(esc(n)).append("</td><td class=\"num\">")
                .append(v < 1000 ? v + " ms" : String.format(Locale.ROOT, "%.1f s", v / 1000.0)).append("</td></tr>\n"));
        StringBuilder sessions = new StringBuilder();
        agentSessions().forEach(s -> sessions.append("<tr><td><code>").append(esc(s.session())).append("</code></td><td class=\"num\">")
                .append(s.stopChecks()).append("</td><td>").append(s.stopChecks() == 0 ? "–" : s.passedFirst() ? "是" : "否")
                .append("</td><td class=\"num\">").append(s.failsBeforePass()).append("</td><td>")
                .append(s.stopChecks() == 0 ? "–" : s.everPassed() ? "是" : "<span class=\"warn\">⚠ 否</span>").append("</td></tr>\n"));
        return TEMPLATE
                .replace("{{range}}", esc(range()))
                .replace("{{source}}", esc(source))
                .replace("{{events}}", String.valueOf(events.size()))
                .replace("{{sessions}}", String.valueOf(k.sessions()))
                .replace("{{firstPass}}", k.firstPass())
                .replace("{{loops}}", k.avgLoops())
                .replace("{{early}}", k.earlyShare())
                .replace("{{escalations}}", String.valueOf(k.escalations()))
                .replace("{{total}}", String.valueOf(k.total()))
                .replace("{{bars}}", bars)
                .replace("{{reasons}}", reasons)
                .replace("{{timing}}", timing)
                .replace("{{sessionRows}}", sessions);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static final String TEMPLATE = """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Harness 质量看板</title>
            <style>
            :root{color-scheme:light;--surface:#fcfcfb;--card:#ffffff;--border:#e4e3df;--text:#0b0b0b;--text-2:#52514e;--grid:#ecebe7;--series-1:#2a78d6;--warn:#b45309}
            @media (prefers-color-scheme:dark){:root{color-scheme:dark;--surface:#1a1a19;--card:#222220;--border:#3a3936;--text:#f0efec;--text-2:#c3c2b7;--grid:#2e2d2a;--series-1:#3987e5;--warn:#f59e0b}}
            *{box-sizing:border-box}body{margin:0;background:var(--surface);color:var(--text);font:14px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC","Segoe UI",sans-serif}
            main{max-width:960px;margin:0 auto;padding:24px 16px 48px}h1{font-size:22px;margin:0 0 4px}h2{font-size:15px;margin:28px 0 10px}
            .sub{color:var(--text-2);font-size:12.5px}.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin-top:20px}
            .kpi{background:var(--card);border:1px solid var(--border);border-radius:10px;padding:14px 16px}.kpi .v{font-size:28px;font-weight:650;font-variant-numeric:tabular-nums}
            .kpi .k{color:var(--text-2);font-size:12.5px}.kpi .h{color:var(--text-2);font-size:11.5px;margin-top:4px}
            .card{background:var(--card);border:1px solid var(--border);border-radius:10px;padding:16px}
            .bar-row{display:grid;grid-template-columns:150px 1fr;align-items:center;gap:12px;padding:6px 0}.bar-label{color:var(--text-2);font-size:13px}.bar-label b{color:var(--text)}
            .bar-track{position:relative;display:flex;align-items:center;gap:8px;border-left:1px solid var(--grid)}
            .bar{height:18px;background:var(--series-1);border-radius:0 4px 4px 0;min-width:2px}.bar-value{font-variant-numeric:tabular-nums;font-size:13px}
            .bar-row:hover .bar{filter:brightness(1.12)}
            table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:7px 8px;border-bottom:1px solid var(--grid);font-size:13px}th{color:var(--text-2);font-weight:500}
            td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}.grid2{display:grid;grid-template-columns:1fr 1fr;gap:16px}.warn{color:var(--warn)}
            code{font-size:12px}@media (max-width:640px){.grid2{grid-template-columns:1fr}.bar-row{grid-template-columns:110px 1fr}}
            </style></head><body><main>
            <h1>Harness 质量看板</h1>
            <div class="sub">{{range}} · {{events}} 条事件 · 数据源 <code>{{source}}</code></div>
            <div class="kpis">
              <div class="kpi"><div class="k">Stop 一次通过率</div><div class="v">{{firstPass}}</div><div class="h">Agent 第一次想结束时就通过</div></div>
              <div class="kpi"><div class="k">平均修复轮数</div><div class="v">{{loops}}</div><div class="h">首次通过前被 Stop 拦下的次数</div></div>
              <div class="kpi"><div class="k">早层拦截占比</div><div class="v">{{early}}</div><div class="h">L0+L1 拦下的问题 / 全部 {{total}} 次</div></div>
              <div class="kpi"><div class="k">升级到人工</div><div class="v">{{escalations}}</div><div class="h">Agent 修不好、交给人的次数</div></div>
              <div class="kpi"><div class="k">Agent 会话</div><div class="v">{{sessions}}</div><div class="h">来自 Claude Code hooks</div></div>
            </div>
            <h2>各层拦截次数：问题被挡在哪一层</h2>
            <div class="card">{{bars}}</div>
            <div class="grid2">
              <div><h2>拦截原因 Top 10</h2><div class="card"><table><thead><tr><th>类别</th><th>原因</th><th class="num">次数</th></tr></thead><tbody>{{reasons}}</tbody></table></div></div>
              <div><h2>检查耗时（中位数）</h2><div class="card"><table><thead><tr><th>检查</th><th class="num">耗时</th></tr></thead><tbody>{{timing}}</tbody></table></div></div>
            </div>
            <h2>Agent 会话明细</h2>
            <div class="card"><table><thead><tr><th>会话</th><th class="num">Stop 检查</th><th>首次即通过</th><th class="num">修复轮数</th><th>最终通过</th></tr></thead><tbody>{{sessionRows}}</tbody></table></div>
            <p class="sub">口径：L0 = PreToolUse 守卫拦截；L1 = 编辑后编译 / speclint / migration-lint 失败；L2 = Stop 或手动 verify 失败；L3 = git pre-commit / CI 失败。由 <code>make report</code> 生成。</p>
            </main></body></html>
            """;
}
