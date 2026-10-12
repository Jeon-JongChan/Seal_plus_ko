"""전용 모델 이전 후 import 연결과 기존 플러그인 설정 import를 정리합니다."""

raise SystemExit("이전 작업 보관용입니다. 원본 소스 보호를 위해 실행하지 않습니다.")
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/plugin'
prefs = (SRC / 'compatibility/PluginPreferences.kt').read_text(encoding='utf-8')
names = re.findall(r'(?:const val|fun)\s+(\w+)', prefs)
for path in SRC.rglob('*.kt'):
    value = path.read_text(encoding='utf-8-sig')
    value = value.replace('import com.plugin.compatibility.PluginPreferences.*',
        '\n'.join('import com.plugin.compatibility.PluginPreferences.' + name for name in names
                  if re.search(r'\b' + name + r'\b', value)))
    for name in names:
        value = value.replace('com.junkfood.seal.util.' + name,
                              'com.plugin.compatibility.PluginPreferences.' + name)
    if path.name == 'ActionSheet.kt' and path.parent.name == 'queue':
        value = value.replace('import com.junkfood.seal.R',
                              'import com.junkfood.seal.R\nimport com.junkfood.seal.ui.page.downloadv2.UiAction')
    path.write_text(value, encoding='utf-8', newline='\n')
