export default defineAppConfig({
  pages: [
    'pages/home/index',
    'pages/workshop/index',
    'pages/mine/index',
    'pages/create/index',
    'pages/detail/index',
    'pages/chapter/index',
  ],
  window: {
    backgroundTextStyle: 'light',
    navigationBarBackgroundColor: '#ffffff',
    navigationBarTitleText: '小说智能体',
    navigationBarTextStyle: 'black',
    backgroundColor: '#f5f7fa',
  },
  tabBar: {
    color: '#86909c',
    selectedColor: '#3b82f6',
    backgroundColor: '#ffffff',
    borderStyle: 'black',
    list: [
      {
        pagePath: 'pages/home/index',
        text: '首页',
      },
      {
        pagePath: 'pages/workshop/index',
        text: '工坊',
      },
      {
        pagePath: 'pages/mine/index',
        text: '我的',
      },
    ],
  },
})
