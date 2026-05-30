
import { load } from 'cheerio';

globalThis.__realCheerio = {
  load: function (html, options) {
    return load(html, options || {}, true);
  },
};
