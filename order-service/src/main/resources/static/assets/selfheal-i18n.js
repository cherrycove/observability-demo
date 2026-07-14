(function () {
  const DEFAULT_LANGUAGE = 'zh';
  const STORAGE_KEY = 'mall-selfheal-demo-lang';
  const SUPPORTED_LANGUAGES = new Set(['zh', 'en']);

  const products = [
    {
      sku: 'sku-1001',
      icon: 'BOOK',
      amountCent: 9900,
      zh: {
        name: '可观测性工程',
        cover: 'assets/observability-engineering-zh.png',
        coverAlt: '《可观测性工程》中文版封面',
        badge: '技术书籍',
        tagline: '从指标、日志、链路、事件到协作流程，系统理解现代可观测性实践。',
        price: '￥99.00',
        note: '纸质书',
        author: 'Charity Majors 等',
        edition: '中文版',
        bullets: ['可观测性工程实践', '故障排查与系统调试', '适合研发、SRE 与平台团队'],
      },
      en: {
        name: 'Observability Engineering',
        cover: 'assets/observability-engineering-en.png',
        coverAlt: 'Observability Engineering English book cover',
        badge: 'Technical book',
        tagline: 'A practical guide to modern observability across telemetry, debugging, and team workflows.',
        price: 'CNY 99.00',
        note: 'Paperback',
        author: 'Charity Majors et al.',
        edition: 'English edition',
        bullets: ['Observability practices', 'Troubleshooting and debugging', 'For engineers, SRE, and platform teams'],
      },
    },
  ];

  const messages = {
    zh: {
      commonLanguage: '语言',
      commonChinese: '中文',
      commonEnglish: 'English',
      commonLoading: '加载中',
      commonNormal: '正常',
      commonUnknown: 'UNKNOWN',
      commonNone: 'none',
      appTitle: '商城 Demo',
      appSubtitle: '图书商城',
      frameTitle: '商城 Demo',
      workbenchTitle: '故障演练工作台 Demo',
      workbenchBadge: '业务场景演练',
      businessScenes: '业务场景',
      businessSceneCount: '1 个场景',
      sceneBookstoreTitle: '商城 Demo',
      sceneBookstoreDescription: '图书商城',
      sceneRunning: '运行中',
      simulatorTitle: '业务场景模拟器',
      previewWeb: 'Web 预览',
      previewMobile: '移动端',
      previewWebOnly: '仅支持 Web',
      browserAddress: 'demo.local/bookstore',
      faultConsoleTitle: '故障注入控制台',
      collapseScenes: '收起业务场景',
      expandScenes: '展开业务场景',
      collapseFaults: '收起故障控制台',
      expandFaults: '展开故障控制台',
      parentTitle: '商城 Demo 与故障注入 Demo',
      parentLead: '左侧商城运行在独立 iframe 中，RUM 只在商城页面初始化；右侧故障注入与日志面板不采集前端 RUM。',
      parentIframeLabel: '图书商城',
      parentOpenShop: '打开独立商城',
      parentRumStatus: '商城 RUM',
      parentRumPending: '等待商城上报',
      parentRumReady: 'RUM 已接入',
      parentRumMissing: 'RUM 未加载',
      parentRumFailed: 'RUM 初始化失败',
      parentFaultPanel: '多层级故障配置',
      parentFaultPanelNote: '右侧注入，左侧购买触发',
      parentLayerLabel: '故障层级',
      parentScenarioLabel: '具体故障',
      parentFaultLoadingTitle: '故障目录加载中',
      parentFaultLoadingDesc: '正在读取可注入的故障场景。',
      parentObservationLoading: '观察信息加载中。',
      parentActiveFault: '当前注入异常',
      parentNoFault: '未注入异常',
      parentNoFaultDetail: '右侧选择故障并点击注入；一次只保留一个异常。',
      parentFaultActiveDetail: '{layer} / {kind}，已注入，等待左侧购买操作触发。',
      parentInjectFault: '注入选中故障',
      parentRecoverFault: '关闭全部故障',
      parentRefreshStatus: '刷新状态',
      faultHistoryTitle: '最近故障注入记录',
      faultHistoryCount: '{count} 条记录',
      faultHistoryEmpty: '注入故障后，操作记录会显示在这里。',
      faultHistoryInject: '注入：{title}',
      faultHistoryPending: '注入中',
      faultHistoryActive: '生效中',
      faultHistoryClosed: '已结束',
      faultHistoryFailed: '失败',
      faultHistoryDuration: '耗时 {duration}',
      faultHistoryTriggers: '触发 {count} 次',
      parentTraceLinkPanel: '链路详情',
      parentTraceLinkPending: '购买成功并匹配到 trace_id 后，可查看对应链路详情。',
      parentTraceLinkReady: '已匹配 trace_id={traceId}，可查看完整链路。',
      parentTraceLinkReadyBatch: '已匹配 {count} 个 trace_id，可查看批量链路。',
      parentTraceLinkFrontendFault: '前端异常在订单请求发出前触发，本次未生成后端 trace_id。',
      parentTraceLinkSlowPending: '慢资源请求已发出，正在匹配对应的后端 trace_id。',
      parentTraceLinkOpen: '打开链路详情',
      parentTraceTags: '链路参数',
      parentTraceTagsNote: '链路检索参数',
      parentLogPanel: '后端链路',
      parentLogPanelNote: '商城事件 / 后端链路',
      parentStatusPanel: '环境状态',
      parentStatusPanelNote: '订单、库存、支付与故障状态',
      parentOrderHealth: 'Order',
      parentInventoryHealth: 'Inventory',
      parentPaymentHealth: 'Payment',
      parentLastRefresh: '未刷新',
      parentLastRefreshAt: '刷新 {time}',
      parentFaultStatus: '故障状态 {mode}',
      parentCatalogFailed: '故障目录加载失败：{message}',
      parentStatusFailed: '状态刷新失败：{message}',
      parentClientFaultInjected: '前端故障已注入：{title}，等待左侧购书操作触发',
      parentClientFaultHintSourceMap: '触发方式：左侧点击“购买这本书”；预期结果：RUM Error 原始堆栈指向 checkout-sourcemap-fault.min.js，上传 SourceMap 后还原到源码 applyCheckoutDiscount。',
      parentClientFaultHintSlow: '触发方式：左侧点击“购买这本书”；预期结果：发起 /api/demo/slow-resource 慢请求，RUM Resource 记录耗时。',
      parentClientFaultHintClick: '触发方式：左侧点击“购买这本书”；预期结果：购买接口不会被调用，商城 UI 记录前端 TypeError。',
      parentBackendFaultInjected: '故障注入 {title} HTTP {status}：{body}',
      parentFaultInjectFailed: '故障注入失败：{title} {message}',
      parentFaultClosed: '故障已关闭',
      parentFaultClosedDetail: '前端和后端故障状态已恢复，可继续购书验证。',
      parentFaultCloseFailed: '关闭全部故障失败：{message}',
      parentShopReady: '图书商城已就绪',
      parentShopOrderResult: '商城购买 HTTP {status}：{body}',
      parentFrontendFaultTriggered: '左侧购书操作触发前端故障：{action}',
      parentShopMessageBlocked: '收到非同源商城消息，已忽略。',
      shopSearchPlaceholder: '搜索书名、作者或主题',
      shopSearchAction: '购书',
      shopNavLabel: '书城导航',
      shopNavHome: '首页',
      shopNavCatalog: '图书分类',
      shopNavTechnology: '技术专区',
      shopNavPurchases: '购书中心',
      shopHeroTitle: '商城 Demo',
      shopHeroText: '选择《可观测性工程》并完成购买，会生成关键业务请求，并把 RUM Action、Resource、Error 与后端 APM/日志串起来。',
      shopProductSection: '精选图书',
      shopProductChip: '书店',
      shopCheckoutTitle: '购买确认',
      shopSelectedLabel: '已选图书',
      shopAmountLabel: '应付金额',
      shopSubscribe: '购买这本书',
      shopBatchSubscribe: '连续购买 5 次',
      shopStatusReady: '等待选择图书',
      shopStatusReadyDetail: '购买后会生成业务请求 ID，并关联后端中文日志。',
      shopStatusSelected: '已选择《{product}》。',
      shopStatusSubmitting: '购书订单提交中',
      shopStatusSubmittingDetail: '正在等待库存预留和支付确认。',
      shopStatusBatchSubmitting: '连续购买中 {current}/{total}',
      shopStatusBatchSubmittingDetail: '正在连续生成关键业务请求并收集 trace_id。',
      shopStatusBatchSuccess: '连续购买完成 {count}/{total}',
      shopStatusBatchSuccessDetail: '已收集 {traceCount} 个 trace_id，右侧链路将按批量条件查询。',
      shopStatusSuccess: '购买成功',
      shopStatusSuccessDetail: '订单号 {orderId}，业务请求 {bizRequestId}。',
      shopTraceLinkPending: '购买成功后打开链路详情',
      shopTraceLinkReady: '打开链路详情 {traceId}',
      shopStatusFailed: '购买失败',
      shopStatusFrontendFault: '前端故障已注入',
      shopStatusFrontendFaultSourceMap: '请点击“购买这本书”触发压缩 JS 空指针，观察 SourceMap 还原。',
      shopStatusFrontendFaultSlow: '请点击“购买这本书”触发慢资源请求。',
      shopStatusFrontendFaultClick: '请点击“购买这本书”触发无响应场景。',
      shopStatusFrontendFaultFailed: '购买未完成',
      shopStatusFrontendFaultNoTrace: '前端异常在订单请求发出前触发，未生成后端链路。',
      shopStatusBackendFault: '故障已注入，请点击“购买这本书”触发并观察 RUM、APM、日志与指标。',
      shopStatusSlowLoading: '前端资源加载中',
      shopStatusSlowLoadingDetail: '正在请求慢资源，RUM Resource 会记录耗时。',
      shopStatusSlowDone: '慢资源请求完成',
      shopStatusSlowDoneDetail: '资源耗时 {elapsedMs}ms，服务端延迟 {delayMs}ms。',
      shopStatusSlowFailed: '慢资源触发失败',
      shopLogTitle: '商城日志',
      shopLogRumInit: 'RUM 已初始化：service={service} env={env} version={version}',
      shopLogRumMissing: 'RUM SDK 未加载，业务接口仍可使用',
      shopLogRumFailed: 'RUM 初始化失败：{message}',
      shopLogSelected: '选择图书：{product}',
      shopLogSubmit: '提交购书订单 HTTP {status}：{body}',
      shopLogSubmitFailed: '提交购书订单失败：{message}',
      shopLogTrafficStart: '开始连续购买 5 次',
      shopLogTrafficDone: '连续购买完成：5 次',
      shopLogTrafficDoneWithTraces: '连续购买完成：成功 {count} 次，匹配 {traceCount} 个 trace_id。',
      shopLogBackendFound: '后端链路日志：biz_request_id={requestId}，匹配到 {count} 条。',
      shopLogBackendMissing: '后端链路日志暂未匹配到 {requestId}',
      shopLogBackendFailed: '后端日志读取失败：{message}',
      shopLogFrontendError: '前端未捕获异常已被浏览器识别：{message}',
      shopLogFrontendRejected: '前端 Promise 未处理异常已被浏览器识别：{message}',
      shopLogFrontendFault: '左侧购书操作触发前端故障：{action}，购买接口不会被调用',
      shopLogSlowResource: '左侧购书操作触发前端慢资源：{action}，耗时 {elapsedMs}ms',
      shopLogSlowResourceFailed: '前端慢资源触发失败：{message}',
      shopOrderModeKey: '关键购买',
      shopOrderModeNormal: '普通购买',
      layerFrontend: '前端',
      layerBackend: '后端',
      layerInfrastructure: '基础设施',
      layerService: '后端',
      layerDependency: '基础设施',
      layerJvm: '基础设施',
      faultService: '后端',
      faultDependency: '基础设施',
      faultFrontend: '前端',
    },
    en: {
      commonLanguage: 'Language',
      commonChinese: '中文',
      commonEnglish: 'English',
      commonLoading: 'Loading',
      commonNormal: 'Normal',
      commonUnknown: 'UNKNOWN',
      commonNone: 'none',
      appTitle: 'Store Demo',
      appSubtitle: 'Bookstore',
      frameTitle: 'Store Demo',
      workbenchTitle: 'Fault Exercise Workbench Demo',
      workbenchBadge: 'Business scenario lab',
      businessScenes: 'Business Scenarios',
      businessSceneCount: '1 scenario',
      sceneBookstoreTitle: 'Store Demo',
      sceneBookstoreDescription: 'Bookstore',
      sceneRunning: 'Running',
      simulatorTitle: 'Business Scenario Simulator',
      previewWeb: 'Web Preview',
      previewMobile: 'Mobile',
      previewWebOnly: 'Web only',
      browserAddress: 'demo.local/bookstore',
      faultConsoleTitle: 'Fault Injection Console',
      collapseScenes: 'Collapse business scenarios',
      expandScenes: 'Expand business scenarios',
      collapseFaults: 'Collapse fault console',
      expandFaults: 'Expand fault console',
      parentTitle: 'Store Demo and Fault Injection',
      parentLead: 'The store runs in an isolated iframe. RUM initializes only inside the store page; the fault controls and log panel on the right do not collect frontend RUM.',
      parentIframeLabel: 'Book store',
      parentOpenShop: 'Open store',
      parentRumStatus: 'Store RUM',
      parentRumPending: 'Waiting for store',
      parentRumReady: 'RUM connected',
      parentRumMissing: 'RUM not loaded',
      parentRumFailed: 'RUM init failed',
      parentFaultPanel: 'Multi-layer Fault Control',
      parentFaultPanelNote: 'Inject on the right, trigger from the store',
      parentLayerLabel: 'Fault layer',
      parentScenarioLabel: 'Scenario',
      parentFaultLoadingTitle: 'Loading fault catalog',
      parentFaultLoadingDesc: 'Reading injectable fault scenarios.',
      parentObservationLoading: 'Observation guide loading.',
      parentActiveFault: 'Active injected fault',
      parentNoFault: 'No injected fault',
      parentNoFaultDetail: 'Choose a fault on the right and inject it. Only one fault stays active at a time.',
      parentFaultActiveDetail: '{layer} / {kind}, injected and waiting for a store purchase action.',
      parentInjectFault: 'Inject selected fault',
      parentRecoverFault: 'Clear all faults',
      parentRefreshStatus: 'Refresh status',
      faultHistoryTitle: 'Recent fault injection records',
      faultHistoryCount: '{count} records',
      faultHistoryEmpty: 'Fault operations will appear here after an injection.',
      faultHistoryInject: 'Inject: {title}',
      faultHistoryPending: 'Injecting',
      faultHistoryActive: 'Active',
      faultHistoryClosed: 'Ended',
      faultHistoryFailed: 'Failed',
      faultHistoryDuration: 'Duration {duration}',
      faultHistoryTriggers: 'Triggered {count} times',
      parentTraceLinkPanel: 'Trace Details',
      parentTraceLinkPending: 'After a purchase matches a trace_id, open the corresponding trace details.',
      parentTraceLinkReady: 'Matched trace_id={traceId}. Open the full trace details.',
      parentTraceLinkReadyBatch: 'Matched {count} trace IDs. Open the batch trace details.',
      parentTraceLinkFrontendFault: 'The frontend error occurred before the order request, so no backend trace_id was created.',
      parentTraceLinkSlowPending: 'The slow resource request was sent. Matching its backend trace_id now.',
      parentTraceLinkOpen: 'Open trace details',
      parentTraceTags: 'Trace Parameters',
      parentTraceTagsNote: 'Trace search parameters',
      parentLogPanel: 'Backend Trace',
      parentLogPanelNote: 'Store events / backend trace',
      parentStatusPanel: 'Environment Status',
      parentStatusPanelNote: 'Order, inventory, payment, and fault status',
      parentOrderHealth: 'Order',
      parentInventoryHealth: 'Inventory',
      parentPaymentHealth: 'Payment',
      parentLastRefresh: 'Not refreshed',
      parentLastRefreshAt: 'Refreshed {time}',
      parentFaultStatus: 'Fault status {mode}',
      parentCatalogFailed: 'Failed to load fault catalog: {message}',
      parentStatusFailed: 'Failed to refresh status: {message}',
      parentClientFaultInjected: 'Frontend fault injected: {title}. Waiting for a book purchase action in the store.',
      parentClientFaultHintSourceMap: 'Trigger: click "Buy this book" in the store. Expected: RUM Error points to checkout-sourcemap-fault.min.js, then SourceMap restores applyCheckoutDiscount.',
      parentClientFaultHintSlow: 'Trigger: click "Buy this book" in the store. Expected: /api/demo/slow-resource appears as a slow RUM Resource.',
      parentClientFaultHintClick: 'Trigger: click "Buy this book" in the store. Expected: no purchase API call and a frontend TypeError is recorded.',
      parentBackendFaultInjected: 'Injected {title} HTTP {status}: {body}',
      parentFaultInjectFailed: 'Fault injection failed: {title} {message}',
      parentFaultClosed: 'Faults cleared',
      parentFaultClosedDetail: 'Frontend and backend faults are recovered. You can purchase again.',
      parentFaultCloseFailed: 'Failed to clear all faults: {message}',
      parentShopReady: 'Book store is ready',
      parentShopOrderResult: 'Store purchase HTTP {status}: {body}',
      parentFrontendFaultTriggered: 'Store purchase action triggered frontend fault: {action}',
      parentShopMessageBlocked: 'Ignored a non-same-origin store message.',
      shopSearchPlaceholder: 'Search title, author, or topic',
      shopSearchAction: 'Buy',
      shopNavLabel: 'Bookstore navigation',
      shopNavHome: 'Home',
      shopNavCatalog: 'Books',
      shopNavTechnology: 'Technology',
      shopNavPurchases: 'Purchases',
      shopHeroTitle: 'Store Demo',
      shopHeroText: 'Choose Observability Engineering and purchase it to create a key business request linking RUM Action, Resource, Error with backend APM and logs.',
      shopProductSection: 'Featured Book',
      shopProductChip: 'Bookstore',
      shopCheckoutTitle: 'Purchase Confirmation',
      shopSelectedLabel: 'Selected book',
      shopAmountLabel: 'Amount due',
      shopSubscribe: 'Buy this book',
      shopBatchSubscribe: 'Buy 5 times',
      shopStatusReady: 'Waiting for book selection',
      shopStatusReadyDetail: 'Purchasing creates a business request ID and correlates backend logs.',
      shopStatusSelected: '"{product}" is selected.',
      shopStatusSubmitting: 'Submitting purchase',
      shopStatusSubmittingDetail: 'Waiting for inventory reservation and payment confirmation.',
      shopStatusBatchSubmitting: 'Buying {current}/{total}',
      shopStatusBatchSubmittingDetail: 'Creating key business requests and collecting trace IDs.',
      shopStatusBatchSuccess: 'Completed {count}/{total} purchases',
      shopStatusBatchSuccessDetail: 'Collected {traceCount} trace IDs; the link on the right uses a batch query.',
      shopStatusSuccess: 'Purchase confirmed',
      shopStatusSuccessDetail: 'Order {orderId}, business request {bizRequestId}.',
      shopTraceLinkPending: 'Open trace details after purchase',
      shopTraceLinkReady: 'Open trace details {traceId}',
      shopStatusFailed: 'Purchase failed',
      shopStatusFrontendFault: 'Frontend fault injected',
      shopStatusFrontendFaultSourceMap: 'Click "Buy this book" to trigger a minified JS null pointer and verify SourceMap restoration.',
      shopStatusFrontendFaultSlow: 'Click "Buy this book" to trigger a slow resource request.',
      shopStatusFrontendFaultClick: 'Click "Buy this book" to trigger an unresponsive frontend scenario.',
      shopStatusFrontendFaultFailed: 'Purchase not completed',
      shopStatusFrontendFaultNoTrace: 'The frontend error occurred before the order request, so no backend trace was created.',
      shopStatusBackendFault: 'Fault injected. Click "Buy this book" to observe RUM, APM, logs, and metrics.',
      shopStatusSlowLoading: 'Loading frontend resource',
      shopStatusSlowLoadingDetail: 'Requesting a slow resource. RUM Resource will record the latency.',
      shopStatusSlowDone: 'Slow resource completed',
      shopStatusSlowDoneDetail: 'Resource took {elapsedMs}ms; server delay {delayMs}ms.',
      shopStatusSlowFailed: 'Slow resource failed',
      shopLogTitle: 'Store log',
      shopLogRumInit: 'RUM initialized: service={service} env={env} version={version}',
      shopLogRumMissing: 'RUM SDK is not loaded; business APIs still work',
      shopLogRumFailed: 'RUM initialization failed: {message}',
      shopLogSelected: 'Selected book: {product}',
      shopLogSubmit: 'Purchase HTTP {status}: {body}',
      shopLogSubmitFailed: 'Purchase failed: {message}',
      shopLogTrafficStart: 'Starting 5 purchase requests',
      shopLogTrafficDone: 'Completed 5 purchase requests',
      shopLogTrafficDoneWithTraces: 'Completed purchase traffic: {count} succeeded, {traceCount} trace IDs matched.',
      shopLogBackendFound: 'Backend trace logs: biz_request_id={requestId}, matched {count} entries.',
      shopLogBackendMissing: 'No backend trace logs matched {requestId} yet',
      shopLogBackendFailed: 'Failed to read backend logs: {message}',
      shopLogFrontendError: 'Browser captured uncaught frontend error: {message}',
      shopLogFrontendRejected: 'Browser captured unhandled promise rejection: {message}',
      shopLogFrontendFault: 'Store action triggered frontend fault: {action}; purchase API will not be called',
      shopLogSlowResource: 'Store action triggered slow frontend resource: {action}, {elapsedMs}ms',
      shopLogSlowResourceFailed: 'Slow frontend resource failed: {message}',
      shopOrderModeKey: 'Key purchase',
      shopOrderModeNormal: 'Normal purchase',
      layerFrontend: 'Frontend',
      layerBackend: 'Backend',
      layerInfrastructure: 'Infrastructure',
      layerService: 'Backend',
      layerDependency: 'Infrastructure',
      layerJvm: 'Infrastructure',
      faultService: 'Backend',
      faultDependency: 'Infrastructure',
      faultFrontend: 'Frontend',
    },
  };

  const faultTexts = {
    frontend_click_error: {
      zh: {
        title: '前端点击空指针错误',
        description: '购书按钮点击后模拟未捕获 TypeError。',
        observation: '操作：右侧注入后，到左侧点击“购买这本书”触发。观察：商城 UI 不进入购买流程；RUM SDK 自动识别 Error，按 fault_id=frontend_click_error 过滤。',
      },
      en: {
        title: 'Frontend click null pointer',
        description: 'Simulates an uncaught TypeError after clicking the purchase button.',
        observation: 'Action: inject on the right, then click "Buy this book" in the store. Expected: no purchase flow starts, and RUM captures an Error filtered by fault_id=frontend_click_error.',
      },
    },
    frontend_slow_resource: {
      zh: {
        title: '前端慢资源',
        description: '浏览器发起慢资源请求，展示 RUM Resource 慢加载。',
        observation: '操作：右侧注入后，到左侧点击“购买这本书”触发。观察：RUM Resource 会出现 /api/demo/slow-resource 慢请求，按 fault_id=frontend_slow_resource 过滤。',
      },
      en: {
        title: 'Frontend slow resource',
        description: 'The browser requests a slow resource to demonstrate RUM Resource latency.',
        observation: 'Action: inject on the right, then click "Buy this book" in the store. Expected: /api/demo/slow-resource appears as a slow RUM Resource filtered by fault_id=frontend_slow_resource.',
      },
    },
    frontend_sourcemap_error: {
      zh: {
        title: 'SourceMap 源码定位错误',
        description: '压缩 JS 包触发空指针，演示 SourceMap 还原源码行。',
        observation: '操作：右侧注入后，到左侧点击“购买这本书”触发。观察：RUM Error 原始堆栈指向 assets/checkout-sourcemap-fault.min.js，上传 SourceMap 后还原到 applyCheckoutDiscount。',
      },
      en: {
        title: 'SourceMap source location error',
        description: 'A minified JS bundle throws a null pointer to demonstrate SourceMap restoration.',
        observation: 'Action: inject on the right, then click "Buy this book" in the store. Expected: RUM Error first points to assets/checkout-sourcemap-fault.min.js, then SourceMap restores applyCheckoutDiscount.',
      },
    },
    order_slow: {
      zh: {
        title: '订单入口慢响应',
        description: '订单服务入口延迟，展示入口服务慢 Span 和 RUM Resource 慢加载。',
        observation: '观察：提交购书订单后 /api/orders 耗时升高；APM 中 order-service 入口 Span 变慢；RUM Resource 也会变慢。',
      },
      en: {
        title: 'Slow order entry',
        description: 'Delays the order service entrypoint to demonstrate a slow entry span and slow RUM Resource.',
        observation: 'Expected: /api/orders latency increases after purchasing; order-service entry span slows down in APM; RUM Resource slows as well.',
      },
    },
    inventory_redis_timeout: {
      zh: {
        title: '库存 Redis 超时',
        description: '库存服务访问 Redis 阻塞超时，展示依赖层故障。',
        observation: '观察：提交购书订单返回 503；APM 中 inventory-service 到 Redis 的 Span 变慢或失败；日志出现模拟 Redis 超时。',
      },
      en: {
        title: 'Inventory Redis timeout',
        description: 'Blocks inventory access to Redis to demonstrate a dependency-layer fault.',
        observation: 'Expected: purchase returns 503; inventory-service Redis span slows or fails in APM; logs show the simulated Redis timeout.',
      },
    },
    payment_slow: {
      zh: {
        title: '支付慢方法',
        description: '支付服务慢方法，展示下游服务慢 Span 和 Profile。',
        observation: '观察：提交购书订单耗时升高；APM 中 payment-service /api/payments/pay Span 变慢；Profile 可看到 sleep 慢方法。',
      },
      en: {
        title: 'Slow payment method',
        description: 'A slow payment method demonstrates downstream latency and profiling.',
        observation: 'Expected: purchase latency increases; payment-service /api/payments/pay span slows in APM; Profile shows the slow method.',
      },
    },
    payment_error: {
      zh: {
        title: '支付 5xx 错误',
        description: '支付服务返回 5xx，展示下游错误 Span。',
        observation: '观察：提交购书订单返回支付失败；APM 中 payment-service 出现 5xx Error Span；日志出现模拟支付服务 5xx。',
      },
      en: {
        title: 'Payment 5xx error',
        description: 'Payment service returns 5xx to demonstrate a downstream error span.',
        observation: 'Expected: purchase reports payment failure; payment-service has a 5xx Error span in APM; logs show the simulated payment 5xx.',
      },
    },
    payment_cpu_burn: {
      zh: {
        title: '支付 CPU 繁忙',
        description: '支付服务短时 CPU 繁忙，展示 JVM/进程指标与慢 Span。',
        observation: '观察：提交购书订单期间 payment-service CPU 升高；JVM/进程指标可看到短时 CPU 繁忙；APM Span 带 fault_id=payment_cpu_burn。',
      },
      en: {
        title: 'Payment CPU burn',
        description: 'Payment service burns CPU briefly to demonstrate JVM/process metrics and slow spans.',
        observation: 'Expected: payment-service CPU rises during purchase; JVM/process metrics show a short CPU spike; APM span carries fault_id=payment_cpu_burn.',
      },
    },
  };

  function normalizeLanguage(language) {
    const normalized = String(language || '').toLowerCase();
    if (normalized.startsWith('zh')) return 'zh';
    if (normalized.startsWith('en')) return 'en';
    return DEFAULT_LANGUAGE;
  }

  function languageFromUrl() {
    try {
      return new URL(window.location.href).searchParams.get('lang');
    } catch (_) {
      return null;
    }
  }

  function detectLanguage() {
    const fromUrl = languageFromUrl();
    if (fromUrl && SUPPORTED_LANGUAGES.has(normalizeLanguage(fromUrl))) {
      return normalizeLanguage(fromUrl);
    }
    try {
      const fromStorage = window.localStorage.getItem(STORAGE_KEY);
      if (fromStorage && SUPPORTED_LANGUAGES.has(normalizeLanguage(fromStorage))) {
        return normalizeLanguage(fromStorage);
      }
    } catch (_) {
      // localStorage can be blocked in private or embedded contexts.
    }
    return DEFAULT_LANGUAGE;
  }

  function interpolate(template, params) {
    return String(template || '').replace(/\{([a-zA-Z0-9_]+)\}/g, (_, key) => {
      const value = params && Object.prototype.hasOwnProperty.call(params, key) ? params[key] : '';
      return value == null ? '' : String(value);
    });
  }

  function t(key, params, language) {
    const lang = normalizeLanguage(language || currentLanguage);
    const table = messages[lang] || messages[DEFAULT_LANGUAGE];
    return interpolate(table[key] || messages[DEFAULT_LANGUAGE][key] || key, params || {});
  }

  function getProductText(product, language) {
    const lang = normalizeLanguage(language || currentLanguage);
    return product[lang] || product[DEFAULT_LANGUAGE];
  }

  function productBySku(sku) {
    return products.find((product) => product.sku === sku) || products[0];
  }

  function faultText(scenario, field, language) {
    const id = typeof scenario === 'string' ? scenario : scenario?.id;
    const lang = normalizeLanguage(language || currentLanguage);
    const localized = faultTexts[id]?.[lang] || faultTexts[id]?.[DEFAULT_LANGUAGE];
    if (localized && localized[field]) return localized[field];
    if (field === 'title') return scenario?.title || id || '-';
    if (field === 'description') return scenario?.description || '-';
    if (field === 'observation') {
      const layer = scenario?.layer || '-';
      return lang === 'en'
        ? `Observe RUM, APM, logs, or metrics with fault_id=${id} and fault_layer=${layer}.`
        : `观察：按 fault_id=${id}、fault_layer=${layer} 过滤 RUM、APM、日志或指标。`;
    }
    return '-';
  }

  function layerLabel(layer, language) {
    const key = {
      frontend: 'layerFrontend',
      service: 'layerService',
      dependency: 'layerDependency',
      jvm: 'layerJvm',
      backend: 'layerBackend',
      infrastructure: 'layerInfrastructure',
    }[layer];
    return key ? t(key, {}, language) : (layer || '-');
  }

  function applyDomTranslations(root) {
    const scope = root || document;
    scope.querySelectorAll('[data-i18n]').forEach((node) => {
      node.textContent = t(node.dataset.i18n);
    });
    scope.querySelectorAll('[data-i18n-placeholder]').forEach((node) => {
      node.setAttribute('placeholder', t(node.dataset.i18nPlaceholder));
    });
    scope.querySelectorAll('[data-i18n-title]').forEach((node) => {
      node.setAttribute('title', t(node.dataset.i18nTitle));
    });
    scope.querySelectorAll('[data-i18n-aria-label]').forEach((node) => {
      node.setAttribute('aria-label', t(node.dataset.i18nAriaLabel));
    });
    scope.querySelectorAll('[data-i18n-value]').forEach((node) => {
      node.setAttribute('value', t(node.dataset.i18nValue));
    });
  }

  function persistLanguage(language) {
    try {
      window.localStorage.setItem(STORAGE_KEY, language);
    } catch (_) {
      // Ignore storage failures.
    }
  }

  function updateUrlLanguage(language) {
    try {
      const url = new URL(window.location.href);
      url.searchParams.set('lang', language);
      window.history.replaceState({}, '', url);
    } catch (_) {
      // Some embedded contexts may not allow history changes.
    }
  }

  let currentLanguage = detectLanguage();

  function setLanguage(language, options) {
    currentLanguage = normalizeLanguage(language);
    document.documentElement.lang = currentLanguage === 'zh' ? 'zh-CN' : 'en';
    applyDomTranslations(document);
    if (!options || options.persist !== false) persistLanguage(currentLanguage);
    if (options && options.updateUrl) updateUrlLanguage(currentLanguage);
    return currentLanguage;
  }

  window.SelfhealI18n = {
    DEFAULT_LANGUAGE,
    STORAGE_KEY,
    products,
    messages,
    normalizeLanguage,
    detectLanguage,
    getLanguage: () => currentLanguage,
    setLanguage,
    t,
    getProductText,
    productBySku,
    faultText,
    layerLabel,
    applyDomTranslations,
  };
})();
