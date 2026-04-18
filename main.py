#!/usr/bin/env python3
"""老挝语↔中文 翻译 Android App (Kivy + 百度翻译API)"""

import os
import json
import hashlib
import random
import threading
import traceback

from kivy.app import App
from kivy.lang import Builder
from kivy.utils import platform
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.properties import StringProperty

KV = '''
#:import dp kivy.metrics.dp
#:import Factory kivy.factory.Factory

<SettingsPopup@Popup>:
    title: '设置'
    size_hint: 0.9, 0.55
    BoxLayout:
        orientation: 'vertical'
        padding: dp(16)
        spacing: dp(10)

        Label:
            text: '百度翻译 APP ID'
            size_hint_y: None
            height: dp(24)
            halign: 'left'
            text_size: self.size
        TextInput:
            id: cfg_app_id
            hint_text: '请输入 APP ID'
            multiline: False
            size_hint_y: None
            height: dp(40)

        Label:
            text: '百度翻译密钥'
            size_hint_y: None
            height: dp(24)
            halign: 'left'
            text_size: self.size
        TextInput:
            id: cfg_secret
            hint_text: '请输入密钥'
            multiline: False
            size_hint_y: None
            height: dp(40)

        Label:
            text: '前往 fanyi-api.baidu.com 注册免费API'
            size_hint_y: None
            height: dp(20)
            font_size: '11sp'
            color: 0.5,0.5,0.5,1

        BoxLayout:
            size_hint_y: None
            height: dp(44)
            spacing: dp(10)
            Button:
                text: '取消'
                on_release: root.dismiss()
            Button:
                text: '保存'
                background_color: 0.23,0.51,0.96,1
                on_release: app.save_settings(cfg_app_id.text, cfg_secret.text); root.dismiss()


BoxLayout:
    orientation: 'vertical'
    padding: dp(12)
    spacing: dp(10)

    # 顶栏
    BoxLayout:
        size_hint_y: None
        height: dp(48)
        Label:
            text: '老挝语翻译'
            font_size: '20sp'
            bold: True
            halign: 'left'
            text_size: self.size
            valign: 'middle'
        Button:
            text: '⚙'
            size_hint_x: None
            width: dp(48)
            background_color: 0,0,0,0
            font_size: '22sp'
            on_release: Factory.SettingsPopup().open()

    # 语言选择
    BoxLayout:
        size_hint_y: None
        height: dp(44)
        spacing: dp(8)

        ToggleButton:
            id: btn_lo_zh
            text: '老挝语 → 中文'
            group: 'lang'
            state: 'down'
            on_state: app.set_lang('lo','zh') if self.state == 'down' else None

        Button:
            text: '⇄'
            size_hint_x: None
            width: dp(44)
            background_color: 0.2,0.4,0.8,1
            font_size: '18sp'
            on_release: app.swap_lang()

        ToggleButton:
            id: btn_zh_lo
            text: '中文 → 老挝语'
            group: 'lang'
            on_state: app.set_lang('zh','lo') if self.state == 'down' else None

    # 输入区
    Label:
        text: app.src_label
        size_hint_y: None
        height: dp(22)
        halign: 'left'
        text_size: self.size
        font_size: '13sp'
        color: 0.6,0.6,0.6,1

    TextInput:
        id: source_input
        hint_text: '输入要翻译的文字...'
        multiline: True
        size_hint_y: 0.35
        font_size: '16sp'
        padding: dp(12)

    # 翻译按钮
    Button:
        id: translate_btn
        text: '翻译'
        size_hint_y: None
        height: dp(48)
        background_color: 0.23,0.51,0.96,1
        font_size: '16sp'
        on_release: app.do_translate()

    # 输出区
    Label:
        text: app.tgt_label + '  ' + app.engine_label
        size_hint_y: None
        height: dp(22)
        halign: 'left'
        text_size: self.size
        font_size: '13sp'
        color: 0.6,0.6,0.6,1

    Label:
        id: output_label
        text: app.output_text
        text_size: self.width - dp(24), None
        size_hint_y: 0.35
        halign: 'left'
        valign: 'top'
        font_size: '16sp'
        color: 0.9,0.9,0.9,1

    # 状态栏
    Label:
        text: app.status_text
        size_hint_y: None
        height: dp(20)
        font_size: '11sp'
        color: 0.4,0.6,0.4,1
'''


class TranslatorApp(App):
    src_label = StringProperty('老挝语')
    tgt_label = StringProperty('中文')
    engine_label = StringProperty('')
    output_text = StringProperty('翻译结果将显示在这里')
    status_text = StringProperty('')

    from_lang = 'lo'
    to_lang = 'zh'

    def build(self):
        self.title = '老挝语翻译'
        Window.clearcolor = (0.06, 0.09, 0.16, 1)
        Clock.schedule_once(self._post_init, 0.5)
        return Builder.load_string(KV)

    def _post_init(self, *args):
        # 设置弹窗
        from kivy.factory import Factory
        Factory.register('SettingsPopup', cls=self._make_settings_popup())
        self.update_status()

    def _make_settings_popup(self):
        from kivy.uix.popup import Popup
        return Popup

    def get_data_dir(self):
        if platform == 'android':
            from android.storage import app_storage_path
            return app_storage_path()
        return os.path.dirname(__file__)

    def get_config_path(self):
        return os.path.join(self.get_data_dir(), 'config.json')

    def load_config(self):
        p = self.get_config_path()
        if os.path.exists(p):
            with open(p) as f:
                return json.load(f)
        return {}

    def save_config(self, cfg):
        with open(self.get_config_path(), 'w') as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)

    def set_lang(self, fr, to):
        self.from_lang = fr
        self.to_lang = to
        names = {'lo': '老挝语', 'zh': '中文'}
        self.src_label = names.get(fr, fr)
        self.tgt_label = names.get(to, to)

    def swap_lang(self):
        self.from_lang, self.to_lang = self.to_lang, self.from_lang
        names = {'lo': '老挝语', 'zh': '中文'}
        self.src_label = names[self.from_lang]
        self.tgt_label = names[self.to_lang]

        app = App.get_running_app()
        if self.from_lang == 'lo':
            app.root.ids.btn_lo_zh.state = 'down'
            app.root.ids.btn_zh_lo.state = 'normal'
        else:
            app.root.ids.btn_zh_lo.state = 'down'
            app.root.ids.btn_lo_zh.state = 'normal'

        out = self.output_text
        if out and out != '翻译结果将显示在这里':
            app.root.ids.source_input.text = out
            self.output_text = '翻译结果将显示在这里'
            self.engine_label = ''

    def do_translate(self):
        text = self.root.ids.source_input.text.strip()
        if not text:
            return
        self.root.ids.translate_btn.disabled = True
        self.root.ids.translate_btn.text = '翻译中...'
        self.output_text = ''
        self.engine_label = ''

        thread = threading.Thread(target=self._translate_thread, args=(text,))
        thread.daemon = True
        thread.start()

    def _translate_thread(self, text):
        cfg = self.load_config()
        baidu_id = cfg.get('baidu_app_id', '')
        baidu_key = cfg.get('baidu_secret_key', '')

        result = None
        error = None

        if baidu_id and baidu_key:
            try:
                result = self._baidu_translate(text, baidu_id, baidu_key)
            except Exception as e:
                error = f'翻译失败: {e}\n{traceback.format_exc()}'
        else:
            error = '请先配置百度翻译API\n(点击右上角 ⚙ 设置)'

        def update_ui(dt):
            if error:
                self.output_text = error
                self.engine_label = ''
            else:
                self.output_text = result or ''
                self.engine_label = '[百度API]'
            self.root.ids.translate_btn.disabled = False
            self.root.ids.translate_btn.text = '翻译'

        Clock.schedule_once(update_ui)

    def _baidu_translate(self, text, app_id, secret_key):
        import requests

        lang_map = {'lo': 'lo', 'zh': 'zh'}
        from_l = lang_map.get(self.from_lang, self.from_lang)
        to_l = lang_map.get(self.to_lang, self.to_lang)

        # 长文本分段（百度API限制6000字符）
        MAX_LEN = 5000
        if len(text) <= MAX_LEN:
            return self._baidu_request(text, from_l, to_l, app_id, secret_key)

        # 分段翻译
        parts = []
        for i in range(0, len(text), MAX_LEN):
            chunk = text[i:i + MAX_LEN]
            parts.append(self._baidu_request(chunk, from_l, to_l, app_id, secret_key))
        return '\n'.join(parts)

    def _baidu_request(self, text, from_l, to_l, app_id, secret_key):
        import requests

        salt = str(random.randint(32768, 65536))
        sign_str = app_id + text + salt + secret_key
        sign = hashlib.md5(sign_str.encode('utf-8')).hexdigest()

        resp = requests.get(
            'https://fanyi-api.baidu.com/api/trans/vip/translate',
            params={
                'q': text,
                'from': from_l,
                'to': to_l,
                'appid': app_id,
                'salt': salt,
                'sign': sign,
            },
            timeout=15,
        )
        data = resp.json()
        if 'trans_result' in data:
            return '\n'.join(item['dst'] for item in data['trans_result'])
        raise Exception(f"百度错误: {data.get('error_msg', data)}")

    def open_settings(self):
        from kivy.uix.popup import Popup
        cfg = self.load_config()
        popup = Popup(title='设置', size_hint=(0.9, 0.55))
        content = Builder.load_string('''
BoxLayout:
    orientation: 'vertical'
    padding: dp(16)
    spacing: dp(10)
    Label:
        text: '百度翻译 APP ID'
        size_hint_y: None
        height: dp(24)
        halign: 'left'
        text_size: self.size
    TextInput:
        id: cfg_app_id
        hint_text: '请输入 APP ID'
        multiline: False
        size_hint_y: None
        height: dp(40)
    Label:
        text: '百度翻译密钥'
        size_hint_y: None
        height: dp(24)
        halign: 'left'
        text_size: self.size
    TextInput:
        id: cfg_secret
        hint_text: '请输入密钥'
        multiline: False
        size_hint_y: None
        height: dp(40)
    Label:
        text: '前往 fanyi-api.baidu.com 注册免费API'
        size_hint_y: None
        height: dp(20)
        font_size: '11sp'
        color: 0.5,0.5,0.5,1
    BoxLayout:
        size_hint_y: None
        height: dp(44)
        spacing: dp(10)
        Button:
            text: '取消'
            on_release: popup.dismiss()
        Button:
            text: '保存'
            background_color: 0.23,0.51,0.96,1
            on_release: app.save_settings(cfg_app_id.text, cfg_secret.text); popup.dismiss()
''')
        popup.content = content
        content.ids.cfg_app_id.text = cfg.get('baidu_app_id', '')
        content.ids.cfg_secret.text = cfg.get('baidu_secret_key', '')
        popup.open()

    def save_settings(self, app_id, secret):
        cfg = self.load_config()
        cfg['baidu_app_id'] = app_id.strip()
        cfg['baidu_secret_key'] = secret.strip()
        self.save_config(cfg)
        self.update_status()

    def update_status(self):
        cfg = self.load_config()
        has_api = bool(cfg.get('baidu_app_id') and cfg.get('baidu_secret_key'))
        if has_api:
            self.status_text = '✓ 已配置百度API'
        else:
            self.status_text = '○ 请配置百度API (右上角 ⚙)'


if __name__ == '__main__':
    TranslatorApp().run()
