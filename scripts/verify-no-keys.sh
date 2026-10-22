#!/usr/bin/env bash
# 校验：源码树中不再残留任何第三方地图 Key / 他人样式 ID
# 用法：在仓库根目录执行  bash scripts/verify-no-keys.sh
# 退出码：0 = 干净，1 = 发现残留

set -u
cd "$(dirname "$0")/.." || exit 2

FAIL=0

echo "== 1. 已知的上游遗留凭证（硬失败）=="
for k in "bdd6ae6c613b2a45b5583197f565423b" "Io41bNEhfVx3CCsud5jjrft3UOpeh2WF" "ed7541b0077ffda0205ff36f2a5633b1"; do
  HIT=$(grep -rn --binary-files=without-match "$k" . 2>/dev/null | grep -v "verify-no-keys.sh" | grep -v "^./evidence/")
  if [ -n "$HIT" ]; then
    echo "  残留: $k"
    echo "$HIT" | sed 's/^/    /'
    FAIL=1
  fi
done
[ $FAIL -eq 0 ] && echo "  OK: 未发现遗留凭证"

echo "== 2. Manifest 中的 Key 占位符必须存在 =="
for p in amapApiKey baiduApiKey googleMapsApiKey; do
  if grep -q "\${$p}" app/src/main/AndroidManifest.xml 2>/dev/null; then
    echo "  OK: \${$p}"
  else
    echo "  缺失: \${$p} 未在 AndroidManifest.xml 中使用"
    FAIL=1
  fi
done

echo "== 3. manifest 中不得出现硬编码的 32 位 key 字面量 =="
HARD=$(grep -nE 'android:value="[0-9a-f]{32}"' app/src/main/AndroidManifest.xml 2>/dev/null)
if [ -n "$HARD" ]; then
  echo "  发现硬编码:"
  echo "$HARD" | sed 's/^/    /'
  FAIL=1
else
  echo "  OK: 无硬编码 32 位 key"
fi

echo "== 4. local.yml 不应被提交 =="
if git ls-files --error-unmatch local.yml >/dev/null 2>&1; then
  echo "  错误: local.yml 已被 git 跟踪，请 git rm --cached local.yml"
  FAIL=1
else
  echo "  OK: local.yml 未被跟踪"
fi

echo
if [ $FAIL -eq 0 ]; then
  echo "全部检查通过 ✅"
else
  echo "存在未通过项 ❌"
fi
exit $FAIL
