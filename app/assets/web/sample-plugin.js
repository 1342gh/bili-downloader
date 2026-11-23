/* 示例插件：演示可用接口
 *
 * 放在 手机/Android/data/com.dsh.biliurl/files/plugins/ 目录下，文件名任意 .js
 * 改完点 App 里的「重新加载」即可生效，不用重装
 */

Bili.plugin({
  name: '示例插件',
  version: '1.0',
  author: 'dsh',

  onLoad(ctx) {
    ctx.log('示例插件已加载');

    // 加一个按钮：一键填入测试链接
    ctx.button('填入测试链接', () => {
      ctx.setUrl('https://www.bilibili.com/video/BV1GJ411x7h7');
      ctx.log('已填入 BV1GJ411x7h7');
    });

    // 再加一个：把格式切成 MP3
    ctx.button('切成 MP3', () => {
      ctx.setOption('content', 2);
      ctx.setOption('format', 'MP3');
      ctx.log('已切换为「只要音频 / MP3」');
    });
  },

  // 点击开始下载前触发
  onStart(opts) {
    // opts 里是当前的完整参数：url / cookie / content / format / resHeight / fps / interp
    this.lastUrl = opts.url;
  },

  // 单个任务成功
  onDone(info) {
    // info: { url, name, size, format }
  },

  // 单个任务失败
  onError(info) {
    // info: { url, message }
  }
});
