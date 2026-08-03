import re
html = open('src/main/resources/webui.html', encoding='utf-8').read()
for pat in ['newAssistantBubble', 'ensureAssistantBubble', 'ensureTurnBubble', 'sealTurn', 'pushHistoryAssistant', 'turnSeq']:
    idxs = [m.start() for m in re.finditer(re.escape(pat), html)]
    print(pat, '->', len(idxs), 'times', idxs[:10])
