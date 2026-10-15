package io.github.wzgrx.dexadapt.report

/**
 * 报告的 HTML 外壳（自包含：样式、脚本都内联，不连网；浅色 / 深色跟随系统；手机上也能看）。
 * 页面里的表格：带 `data-filter` 的输入框按行文字过滤，带 `data-chip` 的按钮按行的 `data-kind` 过滤，表头点一下排序。
 */
object Html {
    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    fun page(title: String, subtitle: String, body: String): String = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>
:root { --bg:#f7f7f5; --card:#fff; --text:#1d1d1f; --muted:#6b6b70; --line:#e3e3e0; --accent:#c2410c;
  --ok:#3f8f5a; --okbg:#e3f2e7; --warn:#a16207; --warnbg:#fdf0d5; --bad:#b42318; --badbg:#fde4e1; --na:#9a9aa0; --nabg:#efefec; }
@media (prefers-color-scheme: dark) { :root { --bg:#141414; --card:#1e1e1f; --text:#ececec; --muted:#a0a0a6; --line:#33333a; --accent:#fb923c;
  --ok:#6cc58a; --okbg:#1f3a28; --warn:#f2c14e; --warnbg:#3d3115; --bad:#f2837a; --badbg:#47201d; --na:#77777d; --nabg:#2a2a2d; } }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--text); font:14px/1.55 -apple-system,"PingFang SC","Noto Sans CJK SC","Microsoft YaHei",sans-serif; }
main { max-width:1200px; margin:0 auto; padding:24px 16px 64px; }
h1 { font-size:22px; margin:0 0 4px; } h2 { font-size:17px; margin:32px 0 10px; }
.sub { color:var(--muted); margin:0 0 20px; }
.cards { display:grid; grid-template-columns:repeat(auto-fill,minmax(170px,1fr)); gap:10px; }
.card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:12px 14px; }
.card b { display:block; font-size:20px; } .card span { color:var(--muted); font-size:12px; }
.tools { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin:8px 0; }
input[type=search] { flex:1 1 220px; min-width:0; padding:7px 10px; border:1px solid var(--line); border-radius:8px; background:var(--card); color:var(--text); }
button.chip { border:1px solid var(--line); background:var(--card); color:var(--text); border-radius:999px; padding:5px 12px; cursor:pointer; font-size:13px; }
button.chip.on { background:var(--accent); border-color:var(--accent); color:#fff; }
.scroll { overflow-x:auto; border:1px solid var(--line); border-radius:10px; background:var(--card); }
table { border-collapse:collapse; width:100%; font-size:13px; }
th, td { padding:6px 10px; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; }
th { position:sticky; top:0; background:var(--card); cursor:pointer; user-select:none; white-space:nowrap; }
tr:last-child td { border-bottom:0; }
code { font:12px/1.4 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; word-break:break-all; }
td.cell { text-align:center; white-space:nowrap; font-size:12px; }
td.id code { white-space:nowrap; word-break:normal; }
.ok { background:var(--okbg); color:var(--ok); } .warn { background:var(--warnbg); color:var(--warn); }
.bad { background:var(--badbg); color:var(--bad); } .na { background:var(--nabg); color:var(--na); }
.legend { display:flex; flex-wrap:wrap; gap:12px; color:var(--muted); font-size:12px; margin:6px 0; }
.legend i { display:inline-block; width:12px; height:12px; border-radius:3px; margin-right:4px; vertical-align:-2px; }
details { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:8px 12px; margin:8px 0; }
summary { cursor:pointer; font-weight:600; } ul { margin:6px 0; padding-left:20px; }
.muted { color:var(--muted); }
</style>
</head>
<body>
<main>
<h1>${esc(title)}</h1>
<p class="sub">${esc(subtitle)}</p>
$body
</main>
<script>
document.querySelectorAll('[data-table]').forEach(function (box) {
  var table = box.querySelector('table'); if (!table) return;
  var rows = Array.prototype.slice.call(table.tBodies[0].rows);
  var input = box.querySelector('input[data-filter]');
  var chips = Array.prototype.slice.call(box.querySelectorAll('button[data-chip]'));
  var count = box.querySelector('[data-count]');
  function apply() {
    var q = input ? input.value.trim().toLowerCase() : '';
    var on = chips.filter(function (c) { return c.classList.contains('on'); }).map(function (c) { return c.getAttribute('data-chip'); });
    var shown = 0;
    rows.forEach(function (r) {
      var kinds = (r.getAttribute('data-kind') || '').split(' ');
      var ok = (!q || r.textContent.toLowerCase().indexOf(q) >= 0) && (on.length === 0 || on.some(function (k) { return kinds.indexOf(k) >= 0; }));
      r.style.display = ok ? '' : 'none'; if (ok) shown++;
    });
    if (count) count.textContent = shown + ' / ' + rows.length;
  }
  if (input) input.addEventListener('input', apply);
  chips.forEach(function (c) { c.addEventListener('click', function () { c.classList.toggle('on'); apply(); }); });
  Array.prototype.forEach.call(table.tHead.rows[0].cells, function (th, i) {
    var asc = true;
    th.addEventListener('click', function () {
      rows.sort(function (a, b) {
        var x = a.cells[i].getAttribute('data-sort') || a.cells[i].textContent, y = b.cells[i].getAttribute('data-sort') || b.cells[i].textContent;
        var nx = parseFloat(x), ny = parseFloat(y);
        var c = (!isNaN(nx) && !isNaN(ny)) ? nx - ny : x.localeCompare(y, 'zh');
        return asc ? c : -c;
      });
      asc = !asc; rows.forEach(function (r) { table.tBodies[0].appendChild(r); });
    });
  });
  apply();
});
</script>
</body>
</html>
"""

    /** 统计卡片。 */
    fun cards(items: List<Pair<String, String>>): String =
        "<div class=\"cards\">" + items.joinToString("") { (v, label) -> "<div class=\"card\"><b>${esc(v)}</b><span>${esc(label)}</span></div>" } + "</div>"

    /**
     * 可过滤、可排序的表：[chips] 是（种类, 文字）的过滤按钮，每行用 [Row.kinds] 标种类。
     */
    data class Row(val cells: List<String>, val kinds: List<String> = emptyList())

    fun table(headers: List<String>, rows: List<Row>, chips: List<Pair<String, String>> = emptyList(), placeholder: String = "搜索"): String = buildString {
        append("<div data-table>")
        append("<div class=\"tools\"><input type=\"search\" data-filter placeholder=\"").append(esc(placeholder)).append("\">")
        chips.forEach { (k, label) -> append("<button class=\"chip\" data-chip=\"").append(esc(k)).append("\">").append(esc(label)).append("</button>") }
        append("<span class=\"muted\" data-count></span></div>")
        append("<div class=\"scroll\"><table><thead><tr>")
        headers.forEach { append("<th>").append(esc(it)).append("</th>") }
        append("</tr></thead><tbody>")
        rows.forEach { r ->
            append("<tr data-kind=\"").append(esc(r.kinds.joinToString(" "))).append("\">")
            r.cells.forEach { append(it) }
            append("</tr>")
        }
        append("</tbody></table></div></div>")
    }

    /** 普通单元格（内容会转义）；[raw] 为真时 [content] 已经是 HTML。 */
    fun td(content: String, cls: String? = null, title: String? = null, sort: String? = null, raw: Boolean = false): String = buildString {
        append("<td")
        cls?.let { append(" class=\"").append(it).append("\"") }
        title?.let { append(" title=\"").append(esc(it)).append("\"") }
        sort?.let { append(" data-sort=\"").append(esc(it)).append("\"") }
        append(">").append(if (raw) content else esc(content)).append("</td>")
    }

    fun code(s: String) = "<code>${esc(s)}</code>"
}
