const path = require('path');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');

// 两个入口：仪表盘（index.html）与教程页（guide.html）共用同一套设计令牌与基础模块
module.exports = {
  entry: {
    app: './js/main.js',
    guide: './js/guide.js',
  },

  output: {
    filename: '[name].bundle.js',
    path: path.resolve(__dirname, 'dist'),
    clean: true,
  },

  module: {
    rules: [
      {
        test: /\.scss$/,
        use: [
          MiniCssExtractPlugin.loader,
          'css-loader',
          'sass-loader',
        ],
      },
    ],
  },

  plugins: [
    new MiniCssExtractPlugin({
      filename: '[name].bundle.css',
    }),
  ],

  optimization: {
    minimize: true,
  },
};
