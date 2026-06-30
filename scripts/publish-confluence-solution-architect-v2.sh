#!/usr/bin/env bash
# Publish Solution-Architect v2 Markdown docs to Confluence (personal space).
#
# Prerequisites:
#   export CONFLUENCE_EMAIL='you@geowealth.com'
#   export CONFLUENCE_API_TOKEN='...'   # https://id.atlassian.com/manage-profile/security/api-tokens
# Optional:
#   CONFLUENCE_BASE_URL=https://geowealth.atlassian.net/wiki
#   CONFLUENCE_SPACE_KEY=~712020:xxxxxxxx   # auto-detected personal space if unset
#   CONFLUENCE_PARENT_TITLE='Solution Architect — KC-Native Auth (v2)'
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCS_DIR="${ROOT}/docs/solution-architect/v2"
BASE_URL="${CONFLUENCE_BASE_URL:-https://geowealth.atlassian.net/wiki}"
EMAIL="${CONFLUENCE_EMAIL:-}"
TOKEN="${CONFLUENCE_API_TOKEN:-}"
PARENT_TITLE="${CONFLUENCE_PARENT_TITLE:-Solution Architect — KC-Native Auth (v2)}"

if [[ -z "$EMAIL" || -z "$TOKEN" ]]; then
  echo "ERROR: set CONFLUENCE_EMAIL and CONFLUENCE_API_TOKEN" >&2
  exit 1
fi

auth=(-u "${EMAIL}:${TOKEN}")
api() {
  curl -sS "${auth[@]}" -H "Accept: application/json" -H "Content-Type: application/json" "$@"
}

resolve_space_key() {
  if [[ -n "${CONFLUENCE_SPACE_KEY:-}" ]]; then
    echo "$CONFLUENCE_SPACE_KEY"
    return
  fi
  api "${BASE_URL}/rest/api/space?type=personal&limit=25" | python3 -c "
import json, sys
data = json.load(sys.stdin)
results = data.get('results') or []
if not results:
    sys.exit('No personal space found for this user')
print(results[0]['key'])
"
}

find_page_id() {
  local space="$1" title="$2"
  local enc_title
  enc_title=$(python3 -c "import urllib.parse; print(urllib.parse.quote('''$title'''))")
  api "${BASE_URL}/rest/api/content?spaceKey=${space}&title=${enc_title}&expand=version" \
    | python3 -c "
import json, sys
data = json.load(sys.stdin)
r = data.get('results') or []
print(r[0]['id'] if r else '')
"
}

md_to_storage_html() {
  local md_file="$1"
  python3 - "$md_file" <<'PY'
import re, sys, pathlib
text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")

def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

out = []
in_code = False
in_table = False
for line in text.splitlines():
    if line.strip().startswith("```"):
        if not in_code:
            lang = line.strip()[3:].strip()
            out.append(f'<ac:structured-macro ac:name="code"><ac:parameter ac:name="language">{esc(lang)}</ac:parameter><ac:plain-text-body><![CDATA[')
            in_code = True
        else:
            out.append(']]></ac:plain-text-body></ac:structured-macro>')
            in_code = False
        continue
    if in_code:
        out.append(line)
        continue
    if line.startswith("|") and "|" in line[1:]:
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if all(set(c) <= set("-:") for c in cells):
            continue
        if not in_table:
            out.append("<table><tbody>")
            in_table = True
        tag = "th" if not out or "<table>" in out[-1] else "td"
        row = "".join(f"<{tag}>{esc(c)}</{tag}>" for c in cells)
        out.append(f"<tr>{row}</tr>")
        continue
    elif in_table:
        out.append("</tbody></table>")
        in_table = False
    if line.startswith("# "):
        out.append(f"<h1>{esc(line[2:])}</h1>")
    elif line.startswith("## "):
        out.append(f"<h2>{esc(line[3:])}</h2>")
    elif line.startswith("### "):
        out.append(f"<h3>{esc(line[4:])}</h3>")
    elif line.startswith("> "):
        out.append(f"<blockquote><p>{esc(line[2:])}</p></blockquote>")
    elif line.strip() == "---":
        out.append("<hr/>")
    elif line.strip() == "":
        out.append("")
    else:
        s = esc(line)
        s = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)
        s = re.sub(r"`([^`]+)`", r"<code>\1</code>", s)
        s = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', s)
        out.append(f"<p>{s}</p>")

if in_table:
    out.append("</tbody></table>")
body = "\n".join(out)
print(body)
PY
}

upsert_page() {
  local space="$1" parent_id="$2" title="$3" body_html="$4"
  local existing
  existing=$(find_page_id "$space" "$title")
  local payload
  payload=$(python3 -c "
import json, sys
title, body, parent, existing = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
data = {
  'type': 'page',
  'title': title,
  'space': {'key': sys.argv[5]},
  'body': {'storage': {'value': body, 'representation': 'storage'}},
}
if parent:
    data['ancestors'] = [{'id': parent}]
if existing:
    data['id'] = existing
    data['version'] = {'number': 2}
print(json.dumps(data))
" "$title" "$body_html" "$parent_id" "$existing" "$space")

  if [[ -n "$existing" ]]; then
    api -X PUT "${BASE_URL}/rest/api/content/${existing}" -d "$payload" >/dev/null
    echo "Updated: $title (id=$existing)"
    echo "$existing"
  else
    local id
    id=$(api -X POST "${BASE_URL}/rest/api/content" -d "$payload" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
    echo "Created: $title (id=$id)"
    echo "$id"
  fi
}

main() {
  local space parent_id body title
  space=$(resolve_space_key)
  echo "Using space: $space"

  body=$(md_to_storage_html "${DOCS_DIR}/README.md")
  parent_id=$(upsert_page "$space" "" "$PARENT_TITLE" "$body" | tail -1)

  # Publish horizontal scaling report (this audit)
  title="Horizontal scaling report"
  body=$(md_to_storage_html "${DOCS_DIR}/11-horizontal-scaling-report.md")
  upsert_page "$space" "$parent_id" "$title" "$body" >/dev/null

  echo "Done. Parent: ${BASE_URL}/spaces/${space}/pages/${parent_id}"
}

main "$@"
