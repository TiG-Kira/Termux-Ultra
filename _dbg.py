p = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\AiTermuxEngine.kt'
s = open(p, encoding='utf-8').read()
start = s.find('return flow {')
end = s.find('sealed class StreamChunk')
print('start', start, 'end', end, 'len', end-start)
print(s[start:end])