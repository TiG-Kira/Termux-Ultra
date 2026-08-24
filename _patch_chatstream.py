# -*- coding: utf-8 -*-
import io

def apply(path, repls):
    with io.open(path, 'r', encoding='utf-8') as f:
        s = f.read()
    for old, new in repls:
        cnt = s.count(old)
        if cnt != 1:
            print("SKIP %s :: count=%d" % (path, cnt))
            continue
        s = s.replace(old, new)
        print("OK   %s" % path)
    with io.open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(s)

base = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app'

apply(base + r'\compose\AiTermuxEngine.kt', [
    ('''    ): Flow<StreamChunk> = flow {
        try {''',
     '''    ): Flow<StreamChunk> {
        // 本地大模型：走设备端 llama.cpp 流式推理
        if (config.provider == "local") {
            return AiLocalModel.chatStreamLocal(config, messages, isCancelled)
        }
        return flow {
        try {'''),
])
print("DONE")