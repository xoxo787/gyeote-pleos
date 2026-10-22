'use strict';
// JSON 파일 하나에 저장하는 아주 작은 저장소. 쓸 때마다 임시 파일에 쓴 뒤 바꿔치기해서 반쯤 쓰인 파일을 남기지 않는다.
const fs = require('node:fs');
const path = require('node:path');

const EMPTY = () => ({ families: [], children: [], events: [], destinations: [], messages: [] });

function openDb(file) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  let data = EMPTY();
  if (fs.existsSync(file)) {
    try {
      data = { ...EMPTY(), ...JSON.parse(fs.readFileSync(file, 'utf8')) };
    } catch (e) {
      // 깨진 파일은 덮어쓰지 않고 옆에 보관한 뒤 새로 시작한다.
      fs.copyFileSync(file, `${file}.broken-${Date.now()}`);
    }
  }
  function save() {
    const tmp = `${file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
    fs.renameSync(tmp, file);
  }
  return { data, save, file, audioDir: path.join(path.dirname(file), 'audio') };
}

module.exports = { openDb };
