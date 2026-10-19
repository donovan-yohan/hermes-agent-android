import pathlib,json,html
out=pathlib.Path(__file__).resolve().parent
index=json.loads((out/'capture-index.json').read_text())
by={}
for c in index: by.setdefault(c['name'],{})[c['theme']]=c
rows=[]
for state,pair in by.items():
 rows.append('<section><h2>'+html.escape(state)+'</h2><div class="pair">'+''.join('<figure><figcaption>'+theme+' · <a href="'+pair[theme]['receipt']+'">receipt</a></figcaption><a href="'+pair[theme]['file']+'"><img loading="lazy" src="'+pair[theme]['file']+'" alt="'+html.escape(theme+' '+state)+'"></a></figure>' for theme in ['dark','light'])+'</div></section>')
text='<!doctype html><meta charset="utf-8"><title>Core cron synthetic Desktop reference</title><style>body{font:15px system-ui;margin:24px;background:#161616;color:#eee}a{color:#b8d7ff}.pair{display:grid;grid-template-columns:1fr 1fr;gap:16px}figure{margin:0}img{width:100%;height:auto}section{margin:32px 0}figcaption{margin:8px 0}</style><h1>Core cron — synthetic Desktop component reference</h1><p>Pin 5fc490e2b34123212d73ef316086f939567a725a · no live backend · not full Desktop shell · 20 paired states / 40 screenshots · 340 assertions. <a href="report.md">Report and limits</a></p>'+''.join(rows)
(out/'gallery.html').write_text(text)
print('gallery:',len(by),'paired sections;',len(index),'canonical images')
