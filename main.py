#!/usr/bin/env python3
"""老挝语↔中文 翻译 App (Kivy + Flask + 语音)"""

import os
import sys
import json
import hashlib
import random
import threading
import time
import traceback
import asyncio
import tempfile

from kivy.app import App
from kivy.lang import Builder
from kivy.utils import platform
from kivy.clock import Clock
from kivy.core.window import Window

from flask import Flask, request, jsonify, render_template_string, send_file

flask_app = Flask(__name__)

_offline_model = None
_offline_tokenizer = None

def get_data_dir():
    if platform == 'android':
        from android.storage import app_storage_path
        return app_storage_path()
    return os.path.dirname(os.path.abspath(__file__))

def get_config_path():
    return os.path.join(get_data_dir(), 'config.json')

def load_config():
    p = get_config_path()
    if os.path.exists(p):
        with open(p) as f:
            return json.load(f)
    return {}

def save_config(cfg):
    with open(get_config_path(), 'w') as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)

def get_offline_model():
    global _offline_model, _offline_tokenizer
    if _offline_model is None:
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
        model_name = 'facebook/nllb-200-distilled-600M'
        cache_dir = os.path.join(get_data_dir(), 'models')
        os.makedirs(cache_dir, exist_ok=True)
        print(f'[离线] 加载模型 {model_name} ...')
        _offline_tokenizer = AutoTokenizer.from_pretrained(model_name, cache_dir=cache_dir)
        _offline_model = AutoModelForSeq2SeqLM.from_pretrained(model_name, cache_dir=cache_dir)
        print('[离线] 模型加载完成')
    return _offline_model, _offline_tokenizer

def baidu_translate(text, from_lang, to_lang, app_id, secret_key):
    import requests
    salt = str(random.randint(32768, 65536))
    sign_str = app_id + text + salt + secret_key
    sign = hashlib.md5(sign_str.encode('utf-8')).hexdigest()
    resp = requests.get(
        'https://fanyi-api.baidu.com/api/trans/vip/translate',
        params={'q': text, 'from': from_lang, 'to': to_lang,
                'appid': app_id, 'salt': salt, 'sign': sign},
        timeout=15)
    data = resp.json()
    if 'trans_result' in data:
        return '\n'.join(item['dst'] for item in data['trans_result'])
    raise Exception(f"百度错误: {data.get('error_msg', '未知')}")

def offline_translate(text, from_lang, to_lang):
    lang_map = {'lo': 'lao_Laoo', 'zh': 'zho_Hans'}
    src = lang_map.get(from_lang, from_lang)
    tgt = lang_map.get(to_lang, to_lang)
    model, tokenizer = get_offline_model()
    tokenizer.src_lang = src
    inputs = tokenizer(text, return_tensors='pt', padding=True, truncation=True, max_length=512)
    tokens = model.generate(**inputs,
                            forced_bos_token_id=tokenizer.lang_code_to_id[tgt],
                            max_length=512)
    result = tokenizer.batch_decode(tokens, skip_special_tokens=True)
    return result[0] if result else ''

def generate_tts(text, lang='zh'):
    import edge_tts
    voice = 'zh-CN-XiaoxiaoNeural' if lang == 'zh' else 'th-TH-PremwadeeNeural'
    async def _gen():
        tmp = tempfile.NamedTemporaryFile(suffix='.mp3', delete=False)
        tmp.close()
        comm = edge_tts.Communicate(text, voice)
        await comm.save(tmp.name)
        return tmp.name
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(_gen())
    finally:
        loop.close()

HTML_PAGE = r'''<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>老挝语翻译</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,sans-serif;background:#0f172a;color:#e2e8f0;
     min-height:100vh;display:flex;flex-direction:column;align-items:center;padding:20px 12px}
h1{font-size:1.4rem;margin-bottom:4px;background:linear-gradient(135deg,#38bdf8,#818cf8);
   -webkit-background-clip:text;-webkit-text-fill-color:transparent}
.sub{color:#94a3b8;margin-bottom:20px;font-size:.85rem}
.container{width:100%;max-width:600px;display:flex;flex-direction:column;gap:14px}
.lang-bar{display:flex;align-items:center;justify-content:center;gap:10px}
.lang-btn{padding:7px 16px;border-radius:18px;border:1px solid #334155;background:#1e293b;
          color:#e2e8f0;cursor:pointer;font-size:.9rem;transition:all .2s}
.lang-btn.active{background:#3b82f6;border-color:#3b82f6;color:#fff}
.swap{width:36px;height:36px;border-radius:50%;border:1px solid #334155;background:#1e293b;
       color:#94a3b8;cursor:pointer;font-size:1.1rem;display:flex;align-items:center;
       justify-content:center;transition:all .2s}
.swap:hover{border-color:#60a5fa;color:#60a5fa;transform:rotate(180deg)}
.panel{background:#1e293b;border-radius:10px;border:1px solid #334155;overflow:hidden}
.panel-hdr{padding:8px 14px;border-bottom:1px solid #334155;font-size:.8rem;color:#94a3b8;
           display:flex;justify-content:space-between;align-items:center}
textarea{width:100%;min-height:140px;padding:14px;border:none;background:transparent;
         color:#e2e8f0;font-size:1rem;line-height:1.5;resize:vertical;outline:none;font-family:inherit}
textarea::placeholder{color:#475569}
.out{padding:14px;min-height:140px;font-size:1rem;line-height:1.5;white-space:pre-wrap;color:#e2e8f0}
.out.empty{color:#475569}
.btn-row{display:flex;gap:12px;align-items:center;justify-content:center;flex-wrap:wrap}
.tbtn{padding:11px 36px;border-radius:8px;border:none;background:linear-gradient(135deg,#3b82f6,#8b5cf6);
      color:#fff;font-size:1rem;cursor:pointer;transition:all .2s;align-self:center}
.tbtn:hover{transform:translateY(-1px);box-shadow:0 4px 15px rgba(59,130,246,.4)}
.tbtn:disabled{opacity:.5;cursor:not-allowed;transform:none;box-shadow:none}
.voice-btn{width:48px;height:48px;border-radius:50%;border:2px solid #ef4444;
           background:#1e293b;color:#ef4444;cursor:pointer;font-size:1.3rem;
           display:flex;align-items:center;justify-content:center;transition:all .2s}
.voice-btn.recording{background:#ef4444;color:#fff;animation:pulse 1s infinite}
@keyframes pulse{0%,100%{box-shadow:0 0 0 0 rgba(239,68,68,.4)}
                 50%{box-shadow:0 0 0 12px rgba(239,68,68,0)}}
.speak-btn{width:44px;height:44px;border-radius:50%;border:1px solid #334155;
           background:#1e293b;color:#22c55e;cursor:pointer;font-size:1.2rem;
           display:flex;align-items:center;justify-content:center;transition:all .2s}
.speak-btn:hover{border-color:#22c55e}
.speak-btn.playing{background:#166534;color:#86efac}
.st{text-align:center;font-size:.78rem;color:#64748b;margin-top:6px}
.tag{padding:2px 8px;border-radius:10px;font-size:.72rem;font-weight:600}
.tag.baidu{background:#166534;color:#86efac}
.tag.nllb{background:#854d0e;color:#fde68a}
.err{background:#451a1a;border:1px solid #991b1b;color:#fca5a5;padding:10px 14px;
     border-radius:8px;display:none;font-size:.85rem}
.settings{position:fixed;top:12px;right:12px;width:34px;height:34px;border-radius:50%;
          border:1px solid #334155;background:#1e293b;color:#94a3b8;cursor:pointer;
          font-size:1rem;display:flex;align-items:center;justify-content:center}
.modal-bg{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.6);
          z-index:100;justify-content:center;align-items:center}
.modal-bg.show{display:flex}
.modal{background:#1e293b;border-radius:12px;border:1px solid #334155;padding:20px;
       width:360px;max-width:90vw}
.modal h2{margin-bottom:14px;font-size:1rem}
.fg{margin-bottom:12px}
.fg label{display:block;font-size:.82rem;color:#94a3b8;margin-bottom:3px}
.fg input{width:100%;padding:7px 10px;border-radius:6px;border:1px solid #334155;
          background:#0f172a;color:#e2e8f0;font-size:.88rem;outline:none}
.fg input:focus{border-color:#3b82f6}
.macts{display:flex;gap:8px;justify-content:flex-end;margin-top:16px}
.macts button{padding:7px 18px;border-radius:6px;border:none;cursor:pointer;font-size:.88rem}
.btn-c{background:#334155;color:#e2e8f0}
.btn-s{background:#3b82f6;color:#fff}
.voice-hint{font-size:.72rem;color:#64748b;text-align:center;margin-top:4px}
.rec-ind{color:#ef4444;font-size:.82rem;font-weight:600}
</style>
</head>
<body>
<button class="settings" onclick="openS()" title="设置">⚙</button>
<h1>🇱🇦 ↔ 🇨🇳 老挝语翻译</h1>
<p class="sub">语音输入 · 语音播报 · 离线支持</p>
<div class="container">
  <div class="lang-bar">
    <button class="lang-btn active" data-from="lo" data-to="zh" onclick="setL(this)">老挝语→中文</button>
    <button class="swap" onclick="swp()">⇄</button>
    <button class="lang-btn" data-from="zh" data-to="lo" onclick="setL(this)">中文→老挝语</button>
  </div>
  <div class="panel">
    <div class="panel-hdr"><span id="sl">老挝语</span><span id="cc">0</span></div>
    <textarea id="src" placeholder="输入文字，或按🎤说话..." oninput="uc()"></textarea>
  </div>
  <div class="panel">
    <div class="panel-hdr"><span id="tl">中文</span><span id="et"></span></div>
    <div id="out" class="out empty">翻译结果将显示在这里</div>
  </div>
  <div class="err" id="err"></div>
  <div class="btn-row">
    <button class="voice-btn" id="vbtn" onclick="toggleV()">🎤</button>
    <button class="tbtn" id="tbtn" onclick="go()">翻译</button>
    <button class="speak-btn" id="sbtn" onclick="speak()">🔊</button>
  </div>
  <div class="voice-hint" id="vhint"></div>
  <div class="st" id="st"></div>
</div>
<div class="modal-bg" id="sm">
  <div class="modal">
    <h2>⚙ 设置</h2>
    <div class="fg"><label>百度翻译 APP ID</label><input id="ai" placeholder="APP ID"></div>
    <div class="fg"><label>百度翻译密钥</label><input id="sk" placeholder="密钥"></div>
    <p style="font-size:.78rem;color:#64748b">fanyi-api.baidu.com 注册免费API</p>
    <div class="macts">
      <button class="btn-c" onclick="closeS()">取消</button>
      <button class="btn-s" onclick="svS()">保存</button>
    </div>
  </div>
</div>
<script>
let f='lo',t='zh',rec=null,recording=false,audio=null;
function setL(e){document.querySelectorAll('.lang-btn').forEach(b=>b.classList.remove('active'));
  e.classList.add('active');f=e.dataset.from;t=e.dataset.to;ul();}
function swp(){[f,t]=[t,f];
  document.querySelectorAll('.lang-btn').forEach(b=>{b.classList.toggle('active',b.dataset.from===f&&b.dataset.to===t);});
  ul();let s=document.getElementById('src'),o=document.getElementById('out');
  if(!o.classList.contains('empty')){s.value=o.textContent;o.textContent='翻译结果将显示在这里';
  o.classList.add('empty');document.getElementById('et').textContent='';}uc();}
function ul(){const n={lo:'老挝语',zh:'中文'};document.getElementById('sl').textContent=n[f];
  document.getElementById('tl').textContent=n[t];}
function uc(){document.getElementById('cc').textContent=document.getElementById('src').value.length;}
async function go(){let txt=document.getElementById('src').value.trim();if(!txt)return;
  let b=document.getElementById('tbtn'),o=document.getElementById('out'),
      er=document.getElementById('err'),tg=document.getElementById('et');
  b.disabled=true;b.textContent='翻译中...';er.style.display='none';o.textContent='';o.classList.remove('empty');tg.textContent='';
  try{let r=await fetch('/translate',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({text:txt,from:f,to:t})});let d=await r.json();
    if(d.error){er.textContent=d.error;er.style.display='block';o.classList.add('empty');}
    else{o.textContent=d.result;
      tg.innerHTML=d.engine==='baidu'?'<span class="tag baidu">百度API</span>':'<span class="tag nllb">离线NLLB</span>';}}
  catch(e){er.textContent='请求失败: '+e.message;er.style.display='block';}
  b.disabled=false;b.textContent='翻译';}
// 语音输入
function initSR(){const SR=window.SpeechRecognition||window.webkitSpeechRecognition;
  if(!SR){document.getElementById('vbtn').disabled=true;
    document.getElementById('vhint').textContent='浏览器不支持语音';return null;}
  const r=new SR();r.continuous=false;r.interimResults=true;
  r.onstart=()=>{recording=true;document.getElementById('vbtn').classList.add('recording');
    document.getElementById('vhint').innerHTML='<span class="rec-ind">● 录音中...</span>说完自动翻译';};
  r.onresult=(e)=>{let txt='';for(let i=e.resultIndex;i<e.results.length;i++)txt+=e.results[i][0].transcript;
    document.getElementById('src').value=txt;uc();};
  r.onend=()=>{recording=false;document.getElementById('vbtn').classList.remove('recording');
    document.getElementById('vhint').textContent='';
    let txt=document.getElementById('src').value.trim();if(txt)go();};
  r.onerror=(e)=>{recording=false;document.getElementById('vbtn').classList.remove('recording');
    let m='语音识别出错';if(e.error==='not-allowed')m='请允许麦克风权限';
    else if(e.error==='no-speech')m='未检测到语音';
    document.getElementById('vhint').textContent=m;setTimeout(()=>{document.getElementById('vhint').textContent='';},3000);};
  return r;}
function toggleV(){if(!rec){rec=initSR();if(!rec)return;}
  if(recording){rec.stop();}else{
    const lm={lo:'lo-LA',zh:'zh-CN'};rec.lang=lm[f]||'zh-CN';
    try{rec.start();}catch(e){rec.stop();setTimeout(()=>rec.start(),100);}}}
// 语音播报
function speak(){let o=document.getElementById('out'),txt=o.textContent.trim();
  if(!txt||o.classList.contains('empty'))return;let b=document.getElementById('sbtn');
  if(audio&&!audio.paused){audio.pause();audio=null;b.classList.remove('playing');return;}
  b.classList.add('playing');b.disabled=true;
  audio=new Audio('/tts?text='+encodeURIComponent(txt)+'&lang='+t);
  audio.oncanplaythrough=()=>{b.disabled=false;audio.play();};
  audio.onended=()=>{b.classList.remove('playing');audio=null;};
  audio.onerror=()=>{b.classList.remove('playing');b.disabled=false;audio=null;
    if('speechSynthesis' in window){const u=new SpeechSynthesisUtterance(txt);
      u.lang=t==='zh'?'zh-CN':'lo-LA';speechSynthesis.speak(u);}};}
// 设置
async function openS(){try{let r=await fetch('/config');let c=await r.json();
  document.getElementById('ai').value=c.baidu_app_id||'';
  document.getElementById('sk').value=c.baidu_secret_key||'';}catch(e){}
  document.getElementById('sm').classList.add('show');}
function closeS(){document.getElementById('sm').classList.remove('show');}
async function svS(){let cfg={baidu_app_id:document.getElementById('ai').value.trim(),
  baidu_secret_key:document.getElementById('sk').value.trim()};
  await fetch('/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(cfg)});
  closeS();ckS();}
async function ckS(){try{let r=await fetch('/status');let s=await r.json();
  let h='';h+=s.has_api?'<span style="color:#22c55e">● 已配置百度API</span>':'<span style="color:#f59e0b">● 未配置API</span>';
  h+=' · ';h+=s.network?'<span style="color:#22c55e">网络可用</span>':'<span style="color:#f59e0b">离线</span>';
  document.getElementById('st').innerHTML=h;}catch(e){}}
ckS();ul();initSR();
</script>
</body></html>'''

@flask_app.route('/')
def index():
    return render_template_string(HTML_PAGE)

@flask_app.route('/translate', methods=['POST'])
def translate():
    data = request.json
    text = data.get('text', '').strip()
    from_lang = data.get('from', 'lo')
    to_lang = data.get('to', 'zh')
    if not text:
        return jsonify({'error': '请输入要翻译的文字'})
    cfg = load_config()
    baidu_id = cfg.get('baidu_app_id', '')
    baidu_key = cfg.get('baidu_secret_key', '')
    if baidu_id and baidu_key:
        try:
            bl = {'lo': 'lo', 'zh': 'zh'}
            result = baidu_translate(text, bl.get(from_lang, from_lang),
                                     bl.get(to_lang, to_lang), baidu_id, baidu_key)
            return jsonify({'result': result, 'engine': 'baidu'})
        except Exception as e:
            print(f'[百度] 失败: {e}')
    try:
        result = offline_translate(text, from_lang, to_lang)
        return jsonify({'result': result, 'engine': 'nllb'})
    except Exception as e:
        traceback.print_exc()
        return jsonify({'error': f'翻译失败: {e}'})

@flask_app.route('/tts')
def tts():
    text = request.args.get('text', '').strip()
    lang = request.args.get('lang', 'zh')
    if not text:
        return 'No text', 400
    try:
        if len(text) > 500:
            text = text[:500]
        path = generate_tts(text, lang)
        return send_file(path, mimetype='audio/mpeg')
    except Exception as e:
        traceback.print_exc()
        return str(e), 500

@flask_app.route('/config', methods=['GET'])
def get_cfg():
    cfg = load_config()
    return jsonify({'baidu_app_id': cfg.get('baidu_app_id', ''),
                    'baidu_secret_key': cfg.get('baidu_secret_key', '')})

@flask_app.route('/config', methods=['POST'])
def set_cfg():
    data = request.json
    cfg = load_config()
    cfg['baidu_app_id'] = data.get('baidu_app_id', '')
    cfg['baidu_secret_key'] = data.get('baidu_secret_key', '')
    save_config(cfg)
    return jsonify({'ok': True})

@flask_app.route('/status')
def status():
    cfg = load_config()
    has_api = bool(cfg.get('baidu_app_id') and cfg.get('baidu_secret_key'))
    import requests as r
    try:
        r.get('https://fanyi-api.baidu.com', timeout=3)
        net = True
    except:
        net = False
    return jsonify({'has_api': has_api, 'network': net})

KV = '''
BoxLayout:
    orientation: 'vertical'
    padding: dp(12)
    spacing: dp(8)
    Label:
        text: '老挝语翻译服务已启动'
        font_size: '18sp'
        bold: True
    Label:
        text: '打开浏览器访问 http://localhost:5001'
        font_size: '13sp'
        color: 0.5,0.5,0.5,1
'''

class TranslatorApp(App):
    def build(self):
        self.title = '老挝语翻译'
        Window.clearcolor = (0.06, 0.09, 0.16, 1)
        threading.Thread(target=self._start_server, daemon=True).start()
        return Builder.load_string(KV)

    def _start_server(self):
        try:
            flask_app.run(host='127.0.0.1', port=5001, debug=False, use_reloader=False)
        except Exception as e:
            print(f'[翻译] 启动失败: {e}')

if __name__ == '__main__':
    TranslatorApp().run()
