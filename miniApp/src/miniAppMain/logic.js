// 小程序逻辑层：页面事件在这里集中调用后端。
// 选择使用薄 logic 层而不是在生成 page.js 中写业务，避免把支付密钥或业务细节放进编译产物。

const DEFAULT_STORES = [
  {
    id: "store-hotpot",
    name: "巷口老火锅",
    category: "川味火锅 · 毛肚鲜切",
    distance: "1.2km",
    rating: "4.9 月售 3260",
    delivery: "起送 ¥20 · 配送 ¥3 · 约 32 分钟",
    cover: "/assets/food-hotpot.webp",
  },
  {
    id: "store-burger",
    name: "大口堡",
    category: "汉堡炸鸡 · 可乐套餐",
    distance: "800m",
    rating: "4.8 月售 2180",
    delivery: "起送 ¥18 · 配送 ¥2 · 约 25 分钟",
    cover: "/assets/food-burger.webp",
  },
  {
    id: "store-noodles",
    name: "兰州牛肉面",
    category: "面食 · 牛肉汤",
    distance: "1.6km",
    rating: "4.7 月售 1890",
    delivery: "起送 ¥15 · 配送 ¥3 · 约 30 分钟",
    cover: "/assets/food-noodles.webp",
  },
  {
    id: "store-drink",
    name: "茶咖研习社",
    category: "奶茶咖啡 · 甜点",
    distance: "2.1km",
    rating: "4.8 月售 1560",
    delivery: "起送 ¥12 · 配送 ¥4 · 约 28 分钟",
    cover: "/assets/food-drink.webp",
  },
];

const DEFAULT_PRODUCTS = [
  {
    id: "prod-hotpot-1",
    name: "招牌牛油锅底",
    price: "¥39.00",
    sales: "月售 1024 · 好评 98%",
    image: "/assets/food-hotpot.webp",
  },
  {
    id: "prod-hotpot-2",
    name: "鲜切毛肚",
    price: "¥29.00",
    sales: "月售 856 · 好评 97%",
    image: "/assets/food-noodles.webp",
  },
  {
    id: "prod-hotpot-3",
    name: "冰粉",
    price: "¥9.90",
    sales: "月售 650 · 好评 99%",
    image: "/assets/food-drink.webp",
  },
];

function api(path) {
  const app = getApp();
  return ((app && app.globalData && app.globalData.apiBase) || "http://127.0.0.1:18092") + path;
}

function toast(title) {
  wx.showToast({ title: title, icon: "none" });
}

function request(path, options) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: api(path),
      method: (options && options.method) || "GET",
      data: options && options.data,
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data);
        } else {
          reject(res.data || res);
        }
      },
      fail: reject,
    });
  });
}

function getCart() {
  const app = getApp();
  if (!app.globalData.cart) {
    app.globalData.cart = [];
  }
  return app.globalData.cart;
}

function cartView() {
  const cart = getCart();
  const total = cart.reduce((sum, item) => sum + item.priceValue * item.quantity, 0);
  return {
    items: cart.map((item) => ({
      id: item.id,
      name: item.name,
      price: "¥" + item.priceValue.toFixed(2),
      quantity: String(item.quantity),
      subtotal: "小计 ¥" + (item.priceValue * item.quantity).toFixed(2),
      image: item.image,
    })),
    total: "¥" + total.toFixed(2),
    empty: cart.length === 0,
    summary: cart.length === 0 ? "购物车为空" : "已选 " + cart.reduce((n, item) => n + item.quantity, 0) + " 件 · ¥" + total.toFixed(2),
  };
}

const logic = {
  onPageLoad(page) {
    const route = page.route || "";
    if (route.endsWith("/pages/home/home")) {
      this.homeLoad(page);
    } else if (route.endsWith("/pages/store/store")) {
      this.storeLoad(page);
    } else if (route.endsWith("/pages/cart/cart")) {
      this.cartLoad(page);
    } else if (route.endsWith("/pages/orders/orders")) {
      this.ordersLoad(page);
    } else if (route.endsWith("/pages/mine/mine")) {
      this.mineLoad(page);
    }
  },

  homeLoad(page) {
    page.setData({ loading: true, stores: DEFAULT_STORES });
    request("/api/stores")
      .then((data) => {
        const stores = data && data.items ? data.items : DEFAULT_STORES;
        page.setData({ stores: stores, loading: false });
      })
      .catch(() => page.setData({ stores: DEFAULT_STORES, loading: false }));
  },

  storeLoad(page) {
    const id = (page.options && page.options.id) || getApp().globalData.selectedStoreId || "store-hotpot";
    getApp().globalData.selectedStoreId = id;
    const store = DEFAULT_STORES.find((item) => item.id === id) || DEFAULT_STORES[0];
    page.setData({
      storeName: store.name,
      slogan: store.category,
      products: DEFAULT_PRODUCTS,
      loading: true,
    });
    request("/api/stores/" + id + "/products")
      .then((data) => {
        const products = data && data.items ? data.items : DEFAULT_PRODUCTS;
        page.setData({ products: products, loading: false });
      })
      .catch(() => page.setData({ products: DEFAULT_PRODUCTS, loading: false }));
  },

  cartLoad(page) {
    page.setData(cartView());
  },

  ordersLoad(page) {
    request("/api/orders")
      .then((data) => {
        const orders = (data && data.items) || [];
        page.setData({ orders: orders, empty: orders.length === 0 });
      })
      .catch(() => page.setData({ orders: [], empty: true }));
  },

  mineLoad(page) {
    const user = wx.getStorageSync("user") || {};
    page.setData({
      nickname: user.nickname || "微信用户",
      phone: user.phone || "",
      loginText: user.phone ? "已登录" : "登录",
      payMode: "支付模式：后端未配置商户密钥时自动进入 mock 验证",
    });
  },

  refresh() {
    const pages = getCurrentPages();
    const current = pages[pages.length - 1];
    if (current) {
      this.onPageLoad(current);
    }
  },

  openStore(id) {
    wx.navigateTo({ url: "/pages/store/store?id=" + id });
  },

  addToCart(id, name, price) {
    const value = Number(String(price).replace("¥", ""));
    const cart = getCart();
    const existing = cart.find((item) => item.id === id);
    if (existing) {
      existing.quantity += 1;
    } else {
      const product = DEFAULT_PRODUCTS.find((item) => item.id === id) || {};
      cart.push({ id: id, name: name, priceValue: value, quantity: 1, image: product.image || "/assets/food-hotpot.webp" });
    }
    toast("已加入购物车");
  },

  adjustQuantity(id, delta) {
    const cart = getCart();
    const index = cart.findIndex((item) => item.id === id);
    if (index < 0) {
      return;
    }
    cart[index].quantity += delta;
    if (cart[index].quantity <= 0) {
      cart.splice(index, 1);
    }
    this.refresh();
  },

  clearCart() {
    getApp().globalData.cart = [];
    this.refresh();
  },

  chooseAddress() {
    wx.chooseAddress({
      success: (res) => {
        const address = (res.provinceName || "") + (res.cityName || "") + (res.countyName || "") + (res.detailInfo || "");
        const pages = getCurrentPages();
        const current = pages[pages.length - 1];
        if (current) {
          current.setData({ address: address });
        }
      },
      fail: () => toast("使用演示地址"),
    });
  },

  selectCoupon() {
    const pages = getCurrentPages();
    const current = pages[pages.length - 1];
    if (current) {
      current.setData({ coupon: "已减 ¥8.00" });
    }
  },

  useCoupon() {
    wx.navigateTo({ url: "/pages/cart/cart" });
  },

  login(phone) {
    if (!String(phone || "").trim()) {
      toast("请输入手机号");
      return;
    }
    request("/api/login", { method: "POST", data: { phone: phone } })
      .then((data) => {
        const user = (data && data.user) || { nickname: "用户" + phone, phone: phone };
        wx.setStorageSync("user", user);
        this.refresh();
        toast("登录成功");
      })
      .catch(() => {
        const user = { nickname: "用户" + phone, phone: phone };
        wx.setStorageSync("user", user);
        this.refresh();
        toast("已进入本地演示登录");
      });
  },

  checkout() {
    const cart = getCart();
    if (cart.length === 0) {
      toast("购物车为空");
      return;
    }
    const app = getApp();
    const body = {
      storeId: app.globalData.selectedStoreId || "store-hotpot",
      items: cart.map((item) => ({ id: item.id, name: item.name, quantity: item.quantity, unitPriceCent: Math.round(item.priceValue * 100) })),
    };
    request("/api/orders", { method: "POST", data: body })
      .then((order) => {
        app.globalData.lastOrderId = order.id;
        wx.setStorageSync("lastOrderId", order.id);
        return this.payOrder(order.id);
      })
      .catch((err) => toast((err && err.message) || "下单失败"));
  },

  payOrder(id) {
    if (!id) {
      toast("缺少订单号");
      return;
    }
    request("/api/orders/" + id + "/pay", { method: "POST", data: {} })
      .then((payment) => {
        if (payment.mock) {
          toast("演示支付成功：" + payment.orderId);
          getApp().globalData.cart = [];
          wx.redirectTo({ url: "/pages/orders/orders" });
          return;
        }
        wx.requestPayment({
          timeStamp: payment.timeStamp,
          nonceStr: payment.nonceStr,
          package: payment.package,
          signType: payment.signType,
          paySign: payment.paySign,
          success: () => {
            getApp().globalData.cart = [];
            wx.redirectTo({ url: "/pages/orders/orders" });
          },
          fail: () => toast("支付已取消"),
        });
      })
      .catch((err) => toast((err && err.message) || "支付下单失败"));
  },
};

module.exports = { logic };
