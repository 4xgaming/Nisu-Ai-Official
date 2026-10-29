package ai.nisu.assistant

import org.json.JSONObject

/**
 * v2.58: trading knowledge for NISU AI. Voice mode reads it on demand with the tool trading_guide (so the live prompt stays small);
 * typed chat gets all of it appended only when the conversation is about trading.
 * NOTE: the texts are raw strings, so they must not contain a dollar sign or three quotes in a row.
 */
object TradingKnowledge {

    /** Short block that goes into the persona prompt (voice and text). */
    const val CORE = """

TRADING (you are also his professional trading assistant): You know trading deeply: crypto exchanges (Binance, Bybit, OKX, Bitget, KuCoin, MEXC, Coinbase, Kraken and others), binary / digital options platforms (Quotex, IQ Option, Pocket Option, Olymp Trade, Binolla and others), forex and CFD brokers with MetaTrader 4 / 5, TradingView charts, spot, margin, futures and options, order types, technical and fundamental analysis, risk management and trading psychology. For any trading question or trade, FIRST call the tool trading_guide with the right topic (platforms, binary, crypto_futures, order_types, analysis, indicators, strategies, risk, psychology, fundamentals, execution, scams, glossary; or all for the list) and answer from it in your own words: short when you speak, complete when he asks for detail.
HONESTY (very important): nobody can predict the market. Never promise profit, never call a trade sure or guaranteed, never say you are certain. Give every idea as a probability-based plan with the reason, entry, stop-loss and target, and say once, briefly and without a lecture, that trading can lose money and that you are not a licensed financial advisor. Binary options need a win rate above 1 divided by (1 + payout) just to break even and most retail traders lose money on them: say that plainly when he trades them, and remind him to check that they are legal where he lives.
TAKING A TRADE FOR HIM: when he tells you to take a trade, you choose the setup yourself from your analysis of what is really on the screen (call trading_guide with topic execution first). You place or close a real order only after you have said the exact details out loud (platform, demo or real account, asset, buy or sell, amount, expiry or leverage, stop-loss and take-profit) and he has said yes; then set user_confirmed true on the tool you use. If he did not say demo or real, ask. Never raise the amount after a loss, never double up, never use more than the amount he gave you, never withdraw, transfer, change security settings or touch API keys, and stop at any login, OTP or captcha screen. If you cannot clearly read the price or the chart, say so and do not guess.
"""

    private val TOPICS: LinkedHashMap<String, String> = linkedMapOf(

        "platforms" to """
PLATFORMS (what each one is and how it works):
CRYPTO EXCHANGES
- Binance: biggest exchange. Products: Spot, Margin, Futures (USDT-M and COIN-M), Options, Convert (simple swap), Earn (savings / staking), P2P (buy and sell crypto with local payment methods and an escrow), Copy Trading, Launchpool / Launchpad, Web3 wallet. In the app: Trade tab, choose Spot or Futures, pick a pair (for example BTC/USDT), choose order type, price and amount, tap Buy / Sell (spot) or Buy-Long / Sell-Short (futures). Futures screen also has leverage, Cross / Isolated margin, TP/SL and Reduce-Only. Fees are maker / taker and have VIP tiers and token discounts, check the fee page because they change.
- Bybit, OKX, Bitget, KuCoin, MEXC, Gate, BingX, HTX: similar spot + perpetual futures + copy trading. Bybit and OKX use a unified account (one balance for spot, derivatives and options). Bitget and BingX are popular for copy trading. Small exchanges list many tiny coins: higher risk of pumps, thin liquidity and delistings.
- Coinbase, Kraken, Bitstamp: regulated in several countries, simpler, mostly spot, higher fees on the simple screens (use the advanced trade screen for lower fees).
- DEXs (Uniswap, PancakeSwap, dYdX, Hyperliquid): trade from your own wallet, no account, but you carry smart-contract, slippage and scam-token risk.
- P2P: you pay the seller by bank or mobile money and the exchange releases the crypto from escrow. Only release crypto after the money is really in YOUR account (check the bank / wallet app itself, never a screenshot). Use verified merchants only.
BINARY / DIGITAL OPTIONS PLATFORMS: Quotex, IQ Option, Pocket Option, Olymp Trade, Binolla and similar. See topic binary.
FOREX / CFD BROKERS: MetaTrader 4 and 5 are the standard apps (also cTrader). Brokers such as Exness, IC Markets, XM, Pepperstone. Check the broker's regulator, spread, commission, swap, leverage and withdrawal reputation. Gold (XAUUSD) and indices are traded as CFDs. See glossary for pip, lot, spread.
STOCKS: Interactive Brokers, eToro, local brokers. Market hours apply; US stocks 9:30 to 16:00 New York time.
CHARTS AND TOOLS: TradingView (best charts, indicators, alerts, Pine scripts), Coinglass (open interest, funding, liquidations), CoinMarketCap / CoinGecko (market caps, listings), Glassnode / CryptoQuant (on-chain), Forex Factory / Investing.com economic calendar.
LEGALITY: crypto and especially binary options are banned or restricted in some countries (binary options are banned for retail clients in the EU, UK and some other places). He must check his own country's rules and use only platforms he can legally use.
""",

        "binary" to """
BINARY / DIGITAL OPTIONS (Quotex-style platforms):
HOW A TRADE WORKS: pick an asset (currency pair, crypto, gold, stock), the amount, the expiry (from seconds to minutes or hours), and the direction: Up / Buy (price will be higher than the entry at expiry) or Down / Sell (lower). If right you receive the stake plus the payout percent (for example 85 percent payout: stake 10, win +8.5, you get 18.5 back). If wrong you lose the whole stake. A tie at expiry usually returns the stake. The trade ends by itself at the expiry time; some platforms allow an early close at a worse payout.
MATHS (the most important fact): break-even win rate = 1 / (1 + payout). Payout 80 percent needs 55.6 percent wins, 85 percent needs 54.1 percent, 90 percent needs 52.6 percent, just to not lose money. Expected value per trade = win rate x payout - (1 - win rate). With 50 percent wins and 85 percent payout you slowly lose about 7.5 percent of every stake. The platform is normally the counterparty, so it earns when traders lose.
OTC PAIRS: when the real market is closed (weekends, nights) platforms show OTC pairs. Their prices come from the platform's own feed, not the real market, so news and real-market analysis do not apply and the behaviour can be odd. Treat them as higher risk.
DEMO ACCOUNT: virtual money to practise. Always test any method on demo for many trades first. Demo results are easier than real because of emotions and sometimes different execution.
HOW TO ANALYSE FOR A SHORT EXPIRY: 1) trend on a higher timeframe (15 minute or 1 hour: EMA 50 direction, higher highs / higher lows); 2) key levels (support / resistance, round numbers); 3) a trigger on the entry timeframe (1 or 5 minute): rejection candle (pin bar, engulfing) at a level in the direction of the trend, RSI turning from 30 / 70, break and retest; 4) check there is no big news in the next 30 minutes; 5) choose the expiry about 2 to 4 times the candle size you used (a 1 minute chart gives 2 to 4 minute expiry); 6) skip when signals disagree. Very short expiries (5 to 30 seconds) are close to a coin flip with noise, avoid them.
RULES FOR HIM: fixed small stake (1 to 2 percent of the balance), never martingale (doubling after a loss is how accounts are wiped), stop after 2 or 3 losses in a row or a daily loss limit of about 3 to 5 percent, one trade at a time, write a journal. Signals from Telegram or YouTube sellers and "bots with 90 percent wins" are almost always scams.
""",

        "crypto_futures" to """
CRYPTO SPOT, MARGIN AND FUTURES:
SPOT: you really own the coin. Risk is the price falling; no liquidation.
MARGIN: borrowed money on spot; interest; liquidation possible.
PERPETUAL FUTURES (Binance, Bybit, OKX and others): contracts that track the price with no expiry. Long = profit when price rises, short = profit when it falls. Leverage multiplies both profit and loss: with 10x leverage a 10 percent move against you wipes the position margin. Position size (notional) = margin x leverage.
MARGIN MODES: Isolated = only the margin put into that position can be lost; Cross = the whole futures balance backs the position (can lose everything). Beginners should use Isolated and low leverage (2x to 5x).
LIQUIDATION: when losses eat the margin the exchange closes the position. The liquidation price is shown on the position screen; keep the stop-loss well before it.
FUNDING RATE: periodic payment between longs and shorts (usually every 8 hours, some contracts more often) that keeps the perpetual price near spot. Positive funding = longs pay shorts (market crowded long). Extreme funding often comes before reversals.
MARK PRICE vs LAST PRICE: liquidation uses the mark price. OPEN INTEREST: total open contracts; rising price + rising OI = new money in the trend; rising OI with flat price = a squeeze may come.
FEES: maker (limit order that adds liquidity) is cheaper than taker (market order); check the fee page. Slippage: market orders in thin markets fill worse than shown.
TAKING A TRADE STEP BY STEP (futures): choose the pair, set margin mode and leverage, choose Limit (price you want) or Market (now), enter the size, attach TP and SL, check the summary (entry, size, liquidation price, estimated loss at SL), then confirm. To end it: use Close position (Market or Limit), or let TP / SL hit, or reduce part of it. In Hedge mode long and short are separate positions; in One-way mode they net.
""",

        "order_types" to """
ORDER TYPES:
- Market: executes now at the best available price (fast, slippage, taker fee).
- Limit: executes only at your price or better (maker fee, may never fill).
- Stop-Market / Stop-Limit: becomes an order when the trigger price is touched; used for stop-loss or breakout entries. Stop-limit can fail to fill in a fast move.
- Take-Profit (TP): closes in profit at a target. Stop-Loss (SL): closes at a loss to cap it.
- OCO (one cancels the other): a TP and an SL together; when one fills the other is cancelled.
- Trailing stop: the stop follows the price by a set distance and locks in profit.
- Post-Only: guarantees maker (cancelled if it would fill instantly). Reduce-Only: can only reduce a position, never open or flip it. IOC / FOK: fill immediately (all or part / all or nothing).
- Conditional / trigger orders, TWAP (splits a large order), grid and DCA bots.
Always set the stop-loss at the moment of entry, and place it where the idea is proven wrong (beyond the swing high / low or a level), not at a random percent.
""",

        "analysis" to """
HOW TO ANALYSE A MARKET (top-down, and always the same routine):
1) HIGHER TIMEFRAME (daily, 4 hour): trend direction and the big support / resistance zones. Trend: higher highs and higher lows = up; lower highs and lower lows = down; sideways = range. Price above the 200 EMA is bullish bias, below is bearish.
2) MIDDLE TIMEFRAME (1 hour, 15 minute): find the zone where price may react: previous highs / lows, round numbers, Fibonacci 0.382 / 0.5 / 0.618 pullback of the last swing, order blocks, fair value gaps, a retest of a broken level.
3) ENTRY TIMEFRAME (5 minute, 1 minute): wait for a trigger there: engulfing or pin bar rejection, break of a minor structure, RSI divergence, volume spike.
4) CONFLUENCE: take a trade only when at least 3 independent things agree (trend + level + trigger + volume or momentum). Otherwise do nothing: no trade is a position.
5) PLAN: entry, stop-loss, take-profit (at least 1:2 risk to reward), position size from the risk rule, the reason, and what would cancel the idea.
CANDLESTICKS: each candle has open, high, low, close. Long wick = rejection. Patterns: hammer / shooting star, bullish / bearish engulfing, doji (indecision), morning / evening star, inside bar. A pattern matters only at a meaningful level and with the trend.
CHART PATTERNS: flags and pennants (continuation), triangles, head and shoulders (reversal), double top / bottom, rising / falling wedge, range break and retest.
MARKET STRUCTURE (smart-money language): BOS = break of structure (trend continues), CHOCH = change of character (possible reversal), liquidity sweep = price takes stops above a high or below a low and reverses, order block = last opposite candle before a strong move, FVG = a gap between candle wicks that price often revisits.
VOLUME: confirms moves; a breakout without volume often fails. Volume profile shows where most trading happened (POC, high-volume nodes act like magnets, low-volume nodes are passed quickly). VWAP is the average price weighted by volume, used by day traders as fair value.
READING THE SCREEN: when you analyse from his phone, only use what is really visible (price, change, timeframe, candles, indicators). If the chart is not readable say so and ask him to read the numbers, never invent levels.
""",

        "indicators" to """
INDICATORS (use few, and never alone):
- Moving averages: SMA / EMA. EMA 9 and 21 for short-term momentum, EMA 50 for medium trend, EMA 200 for the big trend. Price pulling back to a rising EMA 21 / 50 in an uptrend is a classic entry. Golden cross (50 over 200) / death cross are slow signals.
- RSI (14): above 70 overbought, below 30 oversold, but in a strong trend it can stay there for long. Divergence (price makes a new high, RSI does not) warns of weakness. 50 line = trend filter.
- MACD (12, 26, 9): line crossing the signal line and histogram growth show momentum; zero-line cross shows trend change; divergence like RSI.
- Bollinger Bands (20, 2): a squeeze (narrow bands) precedes a big move; walking the band = strong trend; touching the band in a range = mean reversion.
- ATR (14): average candle range, used to set stop distance (for example 1.5 x ATR) and size positions.
- Stochastic (14, 3, 3): momentum in ranges. ADX (14): trend strength, above 25 = trending, below 20 = range.
- Ichimoku: cloud as support / resistance and trend filter; Tenkan / Kijun cross.
- Fibonacci retracement 0.236, 0.382, 0.5, 0.618, 0.786 and extensions 1.272, 1.618 for targets. Pivot points for day trading.
- Crypto data: open interest, funding rate, long / short ratio, liquidation heatmap, exchange net flows, stablecoin supply, BTC dominance.
""",

        "strategies" to """
STRATEGIES (each needs backtesting and demo practice first):
- Trend pullback: trade with the higher-timeframe trend, buy a pullback to EMA 21 / 50 or a Fibonacci 0.5 to 0.618 level with a rejection candle, stop beyond the swing, target the previous high or 1:2 or more.
- Breakout and retest: price breaks a clear level on volume, comes back to retest it, enter on the hold. Beware fake breakouts (liquidity sweeps): wait for the candle close.
- Range trading: buy near range support and sell near resistance with RSI confirmation, stop outside the range; stop when the range breaks.
- Reversal at extremes: divergence + key level + engulfing candle; higher risk, smaller size.
- Scalping: very small targets on 1 to 5 minute charts, needs low fees and fast execution; many trades, discipline is everything.
- Swing trading: hold days to weeks on 4 hour / daily charts; fewer trades, lower stress.
- Smart-money style: wait for a liquidity sweep, then a change of character, enter on an order block or FVG with a tight stop.
- News trading: very risky; spreads widen and prices jump. Beginners should stand aside during high-impact news.
- DCA (buying fixed amounts over time) and grid bots: good for long-term accumulation or ranges, dangerous in a one-way crash.
- Hedging and arbitrage exist but need capital and speed.
Choose ONE strategy, write the rules, test them on demo for at least 50 trades, then trade small.
""",

        "risk" to """
RISK MANAGEMENT (this decides who survives):
- Risk 1 percent (at most 2 percent) of the account per trade. Position size = (balance x risk percent) / (entry price - stop price). Example: balance 1000, risk 1 percent = 10; entry 100, stop 98, distance 2; size = 5 units. With futures leverage the size does not change the risk, the stop does.
- Risk to reward at least 1:2. With 1:2 you can be wrong most of the time and still make money (35 to 40 percent wins is enough, before fees).
- Daily loss limit about 3 percent and weekly limit about 6 to 8 percent: when hit, stop trading for the day. Max 2 or 3 trades open at once and avoid correlated ones (many alts move with BTC).
- Never martingale, never average down a losing leveraged trade, never remove a stop-loss, never move it further away. Moving it to break-even after the trade works is fine.
- Leverage: low (2x to 5x) while learning. Keep the liquidation price far from the stop.
- Only trade money he can afford to lose. Keep emergency savings out. Withdraw part of profits regularly.
- Keep a journal: date, asset, setup, entry, stop, target, result, emotion, mistake. Review weekly.
- Security: 2FA (authenticator app, not SMS), withdrawal whitelist, anti-phishing code, API keys with trade-only permission and no withdrawal right, never share seed phrases or codes.
- Costs: fees, spread, funding and slippage change results a lot for small targets.
""",

        "psychology" to """
TRADING PSYCHOLOGY:
- The market does not care about his opinion. Losses are part of the business; judge decisions by following the plan, not by one result.
- Typical traps: FOMO (chasing a candle that already ran), revenge trading (trading to win back a loss), overtrading, greed (no take-profit), fear (closing winners early), overconfidence after a win streak, moving the stop.
- Rules that help: write the plan before entering, fixed risk per trade, daily loss limit, a maximum number of trades, no trading when tired, angry, drunk or after bad news, take breaks after 2 or 3 losses.
- If he seems to be chasing losses, trading with money he needs or with borrowed money, gently tell him to stop and take a break. Care about him more than about the trade.
""",

        "fundamentals" to """
FUNDAMENTALS AND NEWS:
- Macro: interest-rate decisions (FOMC, central banks), CPI inflation, NFP jobs report, GDP, US dollar index (DXY). Higher rates / strong dollar usually pressure crypto, stocks and gold; rate cuts and weak dollar help. Check the economic calendar (Forex Factory, Investing.com) and avoid trading just before high-impact releases.
- Crypto specific: Bitcoin halving cycle, spot ETF flows, BTC dominance (money rotating between BTC and altcoins), stablecoin supply, exchange inflows / outflows, whale transfers, token unlocks, new listings / delistings, hacks, regulation news, network upgrades, project tokenomics (supply, inflation, vesting), TVL for DeFi, on-chain data (active addresses, realised price, MVRV).
- Sentiment: Fear and Greed index, funding rates, social media hype (extreme hype is often a late-stage warning), long / short ratios.
- Forex: rate differences, inflation, trade balance; sessions: Asia, London, New York; the London to New York overlap is the most active. Gold reacts to real yields, the dollar and fear.
- Always say which information is current and which is from memory; the market changes daily, so check live data on the screen.
""",

        "execution" to """
EXECUTION PROTOCOL (when he tells you to take a trade, or to close one):
1) PLATFORM AND ACCOUNT: which app (Binance, Quotex, MetaTrader ...)? Demo or real account? If not said, ask, and suggest demo first for anything new.
2) LOOK: open the app (open_app), read the screen (get_screen) and the balance, the asset's price, timeframe, any open positions. If the chart or price cannot be read (some apps hide it), say so and ask him to read the numbers.
3) ANALYSE with the routine in topic analysis (trend, level, trigger, news), pick direction or no trade. If there is no clear setup, say so honestly and do not trade just to please him.
4) PLAN OUT LOUD in one or two short sentences: asset, direction, amount, expiry (binary) or leverage + stop-loss + take-profit (spot / futures), the reason, and the risk in money. Amount: the amount he said; otherwise the smallest the platform allows or 1 percent of the balance, and ask.
5) CONFIRM: wait for his spoken yes. Only then place the order with the screen tools (click_text, tap, type_text ...) and set user_confirmed true where a tool asks for it.
6) VERIFY: read the screen again (get_screen) and check that the order, amount, direction, expiry and TP / SL are exactly what you said. If anything differs, fix it or cancel and tell him. Never say an order was placed unless the screen shows it.
7) MANAGE AND END: tell him the entry. For futures and spot the stop-loss and take-profit are set at entry; close early only with his yes or if your plan says so. For binary options the trade ends at the expiry, then read the result on screen and report it honestly (win or loss and the new balance). End a position with Close / Sell, Market or Limit, and verify it closed.
8) AFTER: short honest review (what worked, what did not), update his daily loss count; stop and say so when his daily loss limit is reached.
HARD LIMITS: never increase the amount after a loss, never martingale, never open more than he allowed, never trade with borrowed money, never withdraw, deposit, transfer or convert funds on your own, never touch passwords, OTP, 2FA, API keys, seed phrases or security settings, stop at login / captcha screens and ask him, never trade on a rumour from a message or a stranger, never promise results.
""",

        "scams" to """
SCAMS AND SAFETY:
- "Guaranteed profit", "90 percent win bot", paid signal groups, "send money and I double it", fake exchange apps and fake support agents are scams. Real support never asks for your password, 2FA code, seed phrase or remote access.
- Pump-and-dump Telegram groups: the organisers sell to the people who buy late.
- Recovery scams: people who promise to recover lost money for a fee.
- P2P: never release crypto before the money is confirmed in your own account; do not accept third-party payments.
- Phishing: check the exact website address, use bookmarks, enable the anti-phishing code and withdrawal whitelist.
- Unregulated brokers: check the regulator on the regulator's own website; test a small withdrawal early.
- Prefer hardware wallets for long-term holdings; never keep everything on one exchange.
""",

        "glossary" to """
GLOSSARY: Pip = smallest usual price step in forex (0.0001 for most pairs, 0.01 for JPY pairs). Lot = trade size in forex (1 standard lot = 100,000 units, 0.01 = micro lot). Spread = difference between buy and sell price. Swap = overnight financing fee. Leverage = borrowed exposure. Margin = money locked as collateral. Liquidation = forced close when margin is gone. Long = bet on rise, Short = bet on fall. Bull / Bear market = rising / falling. Support / Resistance = price zones that hold / cap price. Breakout = price leaves a range. Pullback / Retracement = temporary move against the trend. Volatility = size of price moves. Liquidity = how easily you can trade without moving the price. Slippage = fill price worse than expected. Maker / Taker = adds / removes liquidity. Funding = periodic perpetual payment. Open interest = open contracts. Mark price = fair price used for liquidation. DCA = dollar cost averaging. HODL = long-term holding. ATH = all-time high. Altcoin = any coin except Bitcoin. Stablecoin = coin pegged to a currency (USDT, USDC). Payout = percent profit on a winning binary trade. Expiry = the end time of a binary trade. OTC = platform-made prices outside real market hours. Drawdown = fall from the account's peak. Win rate = percent of winning trades. Risk-reward = potential profit divided by potential loss. Backtest = testing a strategy on past data. Demo = practice account with virtual money.
"""
    )

    private val ALIASES: Map<String, List<String>> = mapOf(
        "platforms" to listOf("platform", "exchange", "binance", "bybit", "okx", "bitget", "kucoin", "mexc", "coinbase", "kraken", "forex", "broker", "metatrader", "mt4", "mt5", "tradingview", "p2p", "stock"),
        "binary" to listOf("binary", "quotex", "iq option", "pocket", "olymp", "binolla", "expiry", "digital option", "otc", "option"),
        "crypto_futures" to listOf("future", "leverage", "margin", "liquidation", "funding", "perp", "spot", "long", "short"),
        "order_types" to listOf("order", "limit", "market order", "stop", "take profit", "tp", "sl", "oco", "trailing"),
        "analysis" to listOf("analys", "analyz", "chart", "candle", "trend", "support", "resistance", "pattern", "structure", "price action", "smc", "fvg"),
        "indicators" to listOf("indicator", "rsi", "macd", "ema", "moving average", "bollinger", "fibonacci", "fib", "atr", "ichimoku", "stochastic"),
        "strategies" to listOf("strateg", "scalp", "swing", "breakout", "setup", "dca", "grid", "bot"),
        "risk" to listOf("risk", "money management", "position size", "stop loss", "drawdown", "martingale"),
        "psychology" to listOf("psycholog", "fomo", "emotion", "discipline", "revenge"),
        "fundamentals" to listOf("fundamental", "news", "macro", "fomc", "cpi", "halving", "etf", "on-chain", "onchain", "sentiment"),
        "execution" to listOf("execut", "take a trade", "place", "open trade", "close trade", "enter trade", "auto trade", "trade now"),
        "scams" to listOf("scam", "fraud", "signal", "safe", "security", "phishing"),
        "glossary" to listOf("glossary", "meaning", "term", "pip", "lot", "spread")
    )

    fun index(): String = "Trading topics: " + TOPICS.keys.joinToString(", ") + ". Call trading_guide again with one of these topics (or all for everything)."

    /** The text for a topic; unknown or blank topics get the index so the model can pick again. */
    fun guide(topic: String): JSONObject {
        val t = topic.trim().lowercase()
        if (t == "all" || t == "everything") return JSONObject().put("ok", true).put("topic", "all").put("text", all())
        val key = TOPICS.keys.firstOrNull { it == t }
            ?: ALIASES.entries.firstOrNull { (_, words) -> words.any { t.contains(it) } }?.key
        return if (key == null) JSONObject().put("ok", true).put("topic", "index").put("text", index())
        else JSONObject().put("ok", true).put("topic", key).put("text", TOPICS.getValue(key).trim())
            .put("note", "Answer in your own words in his language. Never promise profit. " + index())
    }

    fun all(): String = TOPICS.values.joinToString("\n") { it.trim() }

    private val TRIGGER = Regex(
        "trad(e|er|ing)|binance|bybit|\\bokx\\b|quotex|binary option|pocket option|iq option|olymp|crypto|bitcoin|\\bbtc\\b|\\beth\\b|forex|candle|futures|leverage|stop.?loss|take.?profit|liquidation|\\brsi\\b|\\bmacd\\b|breakout|scalp|metatrader|tradingview|" +
            "\\u099f\\u09cd\\u09b0\\u09c7\\u09a1|\\u0995\\u09cd\\u09b0\\u09bf\\u09aa\\u09cd\\u099f\\u09cb|\\u09ac\\u09be\\u0987\\u09a8\\u09cd\\u09af\\u09be\\u09a8\\u09cd\\u09b8|\\u0995\\u09cb\\u09af\\u09bc\\u09be\\u099f\\u09c7\\u0995\\u09cd\\u09b8|\\u09ac\\u09bf\\u099f\\u0995\\u09af\\u09bc\\u09c7\\u09a8|\\u09b2\\u09bf\\u09ad\\u09be\\u09b0\\u09c7\\u099c|\\u0995\\u09cd\\u09af\\u09be\\u09a8\\u09cd\\u09a1\\u09c7\\u09b2|\\u091f\\u094d\\u0930\\u0947\\u0921",
        RegexOption.IGNORE_CASE
    )

    /** True when a text (the new message plus a few earlier lines) is about trading. Used by typed chat to attach the knowledge only when needed. */
    fun relevant(text: String): Boolean = TRIGGER.containsMatchIn(text)
}
