'use strict';
// `node --test server/test` 는 디렉터리를 모듈로 읽으므로, 이 파일이 테스트 묶음의 입구가 된다.
require('./api.test.js');
require('./pair.test.js');
