// BusManager - Copyright (c) 2026 Francesco Corvi
// Free software under the GNU General Public License, version 3 or later.
// No warranty of any kind; see the LICENSE file in this repository.

// =====================================================================
// BusManagerGUI - the window: add, remove, meter, listen
//
//   name        ch  rate     index   level          listen  x
//   out1_2       2  audio        0   ......
//   reverb       2  audio       16   |||||...        listen  x
//
// One row per bus. The name cell is editable and renames it, the meter
// shows one bar per channel so you can see which side is sounding, and
// listen routes that bus to the monitor output until you click it again.
//
// Metering follows the window: it is switched on when this opens and
// off when it closes, because a handful of osc messages a second is
// worth paying for only while somebody is looking. Listening does not -
// it is routing, and it stays up until you stop it.
// =====================================================================

BusManagerGUI {
	classvar <colBg, <colPanel, <colText, <colDim, <colAccent;
	classvar <colMeter, <colClip, <colOff;

	var <window, scroller, rowsView, rows;
	var ctrl, updater, meterWasOn, lastStatus;
	var outBox, ampSlider, meterBox, statusText;
	var nameField, chanBox, rateMenu, layoutMenu, layoutPaths;
	var font, smallFont, boldFont;

	*initClass {
		colBg     = Color(0.13, 0.13, 0.15);
		colPanel  = Color(0.20, 0.20, 0.24);
		colText   = Color(0.86, 0.87, 0.89);
		colDim    = Color(0.55, 0.56, 0.62);
		colAccent = Color(0.98, 0.65, 0.20);
		colMeter  = Color(0.42, 0.76, 0.95);
		colClip   = Color(0.95, 0.35, 0.30);
		colOff    = Color(0.09, 0.10, 0.12);
	}

	*new { |x, y| ^super.new.init(x, y) }

	init { |x, y|
		rows = [];
		font = Font(Font.defaultSansFace, 11);
		smallFont = Font(Font.defaultSansFace, 9);
		boldFont = Font(Font.defaultSansFace, 12, true);
		this.prBuild(x, y);
		ctrl = SimpleController(BusManager)
			.put(\buses, { { this.prRebuildRows; this.prRefreshHeader }.defer })
			.put(\monitor, { { this.prRefreshRows; this.prRefreshHeader }.defer })
			.put(\meter, { { this.prRefreshHeader }.defer })
			.put(\layouts, { { this.prRefreshLayouts }.defer });
		meterWasOn = BusManager.metering;
		BusManager.meter_(true);
		this.prRebuildRows;
		this.prRefreshHeader;
		this.prRefreshLayouts;
		this.prStartUpdater;
		window.front;
		^this
	}

	front { window !? { if (window.isClosed.not) { window.front } } }

	close { window !? { if (window.isClosed.not) { window.close } } }

	// --------------------------------------------------------------- build

	prBuild { |x, y|
		window = Window("bus manager", Rect(x ? 120, y ? 120, 680, 420));
		if (GUI.id == \qt) { window.view.palette = QPalette.dark };
		window.view.background = colBg;

		rowsView = View().layout_(VLayout().spacing_(3).margins_(0));
		scroller = ScrollView().hasBorder_(false).background_(colBg);
		scroller.canvas = rowsView;

		window.layout = VLayout(
			this.prHeaderRow,
			this.prColumnRow,
			[scroller, stretch: 1],
			this.prAddRow,
			this.prStatusRow
		).margins_(8).spacing_(6);

		window.onClose = {
			ctrl.remove;
			updater !? (_.stop);
			// leave metering as we found it, and leave listening alone
			if (meterWasOn.not) { BusManager.meter_(false) };
			BusManager.prGuiClosed;
		};
	}

	prHeaderRow {
		outBox = NumberBox().fixedWidth_(46).decimals_(0).clipLo_(0).clipHi_(1023)
			.action_({ |v| BusManager.monitorOut_(v.value.asInteger) });
		ampSlider = Slider().orientation_(\horizontal).fixedWidth_(80).fixedHeight_(18)
			.action_({ |v| BusManager.monitorAmp_(v.value * 2) });
		meterBox = CheckBox().string_("meters")
			.action_({ |v| BusManager.meter_(v.value) });
		layoutMenu = PopUpMenu().fixedWidth_(112)
			.action_({ |v|
				var i = v.value ? 0;
				var path = if (i > 0) { layoutPaths[i - 1] } { nil };
				v.value = 0;
				path !? { BusManager.loadLayout(path) };
			});

		^HLayout(
			StaticText().string_("bus manager").font_(boldFont)
				.stringColor_(colAccent).fixedWidth_(88),
			nil,
			this.prLabel("monitor out", 68), outBox,
			this.prLabel("level", 30), ampSlider,
			meterBox,
			this.prButton("save layout", 76, {
				Dialog.savePanel({ |p| BusManager.saveLayout(p); this.prRefreshLayouts },
					path: BusManager.ensureLayoutDir);
			}),
			layoutMenu
		).spacing_(4)
	}

	prColumnRow {
		^HLayout(
			this.prLabel("name", 120),
			this.prLabel("ch", 26),
			this.prLabel("rate", 52),
			this.prLabel("index", 44),
			this.prLabel("level", 60),
			nil,
			this.prLabel("", 52),
			this.prLabel("", 24)
		).spacing_(4)
	}

	prAddRow {
		nameField = TextField().fixedWidth_(120).font_(font)
			.action_({ this.prAddFromFields });
		chanBox = NumberBox().fixedWidth_(40).decimals_(0).clipLo_(1).clipHi_(128)
			.value_(2);
		rateMenu = PopUpMenu().fixedWidth_(72).items_(["audio", "control"]);

		^HLayout(
			this.prLabel("add", 26),
			nameField,
			this.prLabel("ch", 20), chanBox,
			this.prLabel("rate", 28), rateMenu,
			this.prButton("add", 46, { this.prAddFromFields }),
			nil
		).spacing_(4)
	}

	prStatusRow {
		statusText = StaticText().font_(smallFont).stringColor_(colDim);
		^HLayout(statusText).spacing_(4)
	}

	prAddFromFields {
		var name = nameField.string.stripWhiteSpace;
		var rate = if (rateMenu.value == 1) { \control } { \audio };
		if (name.isEmpty) {
			"BusManager: give the bus a name first".warn;
			^this
		};
		if (BusManager.includes(name)) {
			("BusManager: % already exists - it is being replaced".format(name)).postln;
		};
		BusManager.add(name.asSymbol, chanBox.value.asInteger, rate);
		nameField.string = "";
	}

	// ---------------------------------------------------------------- rows

	prRebuildRows {
		var items;
		rows = [];
		rowsView.children.copy.do(_.remove);
		items = BusManager.entries.collect { |entry| this.prMakeRow(entry) };
		if (items.isEmpty) {
			items = [
				StaticText().string_("no buses yet - add one below")
					.font_(font).stringColor_(colDim).fixedHeight_(22)
			];
		};
		rowsView.layout = VLayout(*(items ++ [nil])).spacing_(3).margins_(0);
		this.prRefreshRows;
	}

	prMakeRow { |entry|
		var row = View().fixedHeight_(22);
		var nameBox, meterView, listenBtn;

		nameBox = TextField().fixedWidth_(120).font_(font)
			.string_(entry.name.asString)
			.action_({ |v|
				var wanted = v.string.stripWhiteSpace;
				if (wanted.isEmpty or: { wanted == entry.name.asString }) {
					v.string = entry.name.asString;
				} {
					BusManager.rename(entry.name, wanted.asSymbol);
				};
			});

		meterView = UserView().fixedHeight_(16);
		meterView.drawFunc = { |uv| this.prDrawMeter(uv, entry) };

		listenBtn = Button().fixedWidth_(52)
			.action_({
				if (BusManager.isListeningTo(entry.name)) {
					BusManager.listenOff
				} {
					BusManager.listen(entry.name)
				};
			});

		row.layout = HLayout(
			nameBox,
			this.prCell(entry.numChannels.asString, 26),
			this.prCell(entry.rate.asString, 52),
			this.prCell(entry.index.asString, 44),
			[meterView, stretch: 1],
			listenBtn,
			this.prButton("x", 24, { BusManager.remove(entry.name) })
		).margins_(0).spacing_(4);

		rows = rows.add((entry: entry, meter: meterView, listen: listenBtn));
		^row
	}

	prRefreshRows {
		rows.do { |r|
			var entry = r[\entry];
			var on = BusManager.isListeningTo(entry.name);
			// a control bus has nothing to listen to, so it says so instead
			if (entry.isAudio) {
				r[\listen].states_([[
					"listen",
					if (on) { Color.black } { colText },
					if (on) { colAccent } { colPanel }
				]]);
				r[\listen].enabled = true;
			} {
				r[\listen].states_([["-", colDim, colPanel]]);
				r[\listen].enabled = false;
			};
		};
	}

	// one bar per channel, so you can see which side is sounding
	prDrawMeter { |uv, entry|
		var w = uv.bounds.width, h = uv.bounds.height;
		var levels = entry.levels;
		var n = levels.size.max(1);
		var gap = if (n > 1) { 1 } { 0 };
		var barH = ((h - ((n - 1) * gap)) / n).max(2);

		if (entry.isAudio.not) {
			Pen.stringLeftJustIn(
				levels.collect { |v| (v ? 0).round(0.001).asString }.join("  "),
				Rect(2, 0, w - 4, h), font, colText);
			^this
		};

		levels.do { |lv, i|
			var amp = (lv ? 0).abs.clip(0, 1.5);
			// square root, so the quiet end of the scale is where the eye is
			var scaled = amp.sqrt.clip(0, 1);
			var top = i * (barH + gap);
			Pen.fillColor = colOff;
			Pen.fillRect(Rect(0, top, w, barH));
			Pen.fillColor = if (amp > 0.98) { colClip } { colMeter };
			Pen.fillRect(Rect(0, top, w * scaled, barH));
		};
	}

	// ------------------------------------------------------------- refresh

	prRefreshHeader {
		outBox.value = BusManager.monitorOut;
		ampSlider.value = BusManager.monitorAmp / 2;
		meterBox.value = BusManager.metering;
	}

	prRefreshLayouts {
		layoutPaths = BusManager.layouts;
		layoutMenu.items = ["load layout"] ++ layoutPaths.collect { |p|
			PathName(p).fileNameWithoutExtension
		};
		layoutMenu.value = 0;
		layoutMenu.toolTip = if (layoutPaths.isEmpty) {
			"no layouts yet - save one and it appears here"
		} {
			"newest first:\n" ++ layoutPaths.join("\n")
		};
	}

	prRefreshStatus {
		var listening = BusManager.monitoring;
		var status = "% bus% · %".format(
			BusManager.size,
			if (BusManager.size == 1) { "" } { "es" },
			if (BusManager.server.serverRunning) {
				if (BusManager.metering) { "metering" } { "meters off" }
			} {
				"server not running - buses are reserved, nothing is metered"
			}
		);
		if (listening.notNil) { status = status + "· listening to" + listening.name };
		if (status != lastStatus) {
			lastStatus = status;
			statusText.string = status;
		};
	}

	prStartUpdater {
		updater = Routine({
			var wait = 1 / BusManager.meterRate;
			loop {
				wait.wait;
				if (window.isClosed) {
					updater.stop;
				} {
					if (BusManager.metering) { rows.do { |r| r[\meter].refresh } };
					this.prRefreshStatus;
				};
			};
		}).play(AppClock);
	}

	// -------------------------------------------------------------- widgets

	prLabel { |str, width|
		^StaticText().string_(str).font_(smallFont).stringColor_(colDim)
			.fixedWidth_(width)
	}

	prCell { |str, width|
		^StaticText().string_(str).font_(font).stringColor_(colText)
			.fixedWidth_(width)
	}

	prButton { |str, width, func|
		^Button().fixedWidth_(width).states_([[str, colText, colPanel]])
			.action_({ func.value })
	}
}
