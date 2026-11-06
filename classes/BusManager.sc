// BusManager - Copyright (c) 2026 Francesco Corvi
// Free software under the GNU General Public License, version 3 or later.
// No warranty of any kind; see the LICENSE file in this repository.

// =====================================================================
// BusManager - buses with names, and a window to see them in
//
// Routing by index is opaque: `m.outBus = 4` says nothing about what 4
// is, and 4 moves the moment something else is allocated first. This
// keeps a registry of buses under names, so the patch says what it
// means:
//
//   BusManager.add(\reverb, 2);          // allocate a stereo bus
//   BusManager.index(\reverb);           // -> 16, or wherever it landed
//   m.outBus = \reverb;                  // in any ModuleCommon module
//   { Out.ar(BusManager.index(\reverb), sig) }.play;
//
//   BusManager.gui;                      // add, remove, meter, listen
//
// Everything here is class side: there is one registry per session, the
// way there is one server. The entries are kept in an Array rather than
// a Dictionary because the window lists them and the order they were
// added in is the order that makes sense to read - and with a handful of
// buses, looking one up by walking the array costs nothing.
//
// Two things the registry has to get right on its own:
//
//   - a server boot resets the language side bus allocator, so every bus
//     it owns is taken again on ServerBoot and anything bound to the
//     name follows it there;
//   - listening to a bus that overlaps the monitor output is a feedback
//     loop, so it is refused rather than played.
//
// See BusManagerEntry for what one entry is, and what \owned, \fixed and
// \external mean.
// =====================================================================

BusManager {
	classvar <entries;					// BusManagerEntry, in the order they were added
	classvar server;					// read through *server, which fills it in lazily
	classvar <guiObj;
	classvar <metering = false;
	classvar <monitoring;				// the entry being listened to, nil if none
	classvar <monitorOut = 0, <monitorAmp = 0.5;
	classvar group, monitorSynth, levelFunc, defs, idCounter, hooksAdded;
	classvar bootFunc, treeFunc, quitFunc;

	*initClass {
		entries = [];
		defs = IdentityDictionary.new;
		idCounter = 0;
		hooksAdded = false;
	}

	// Server.default is read the first time it is needed, not in initClass,
	// where the class initialisation order does not promise it exists yet.
	*server { ^server ?? { server = Server.default } }

	*server_ { |aServer|
		if (aServer === this.server) { ^this };
		this.prRemoveHooks;
		this.clear;
		server = aServer;
		^this
	}

	// ------------------------------------------------------------ the registry

	// Allocate a bus and keep it under a name. Re-using a name replaces what
	// was there, freeing it first, so re-evaluating a setup block does not
	// leak buses - the same rule modules follow when a name is re-used.
	*add { |name, channels = 2, rate = \audio|
		var bus;
		this.prEnsureSetup;
		channels = channels.asInteger.max(1);
		rate = rate.asSymbol;
		bus = if (rate == \control) {
			Bus.control(this.server, channels)
		} {
			Bus.audio(this.server, channels)
		};
		if (bus.isNil or: { bus.index.isNil }) {
			("BusManager: the server has no % buses left for %"
				.format(rate, name.asCompileString)).warn;
			^nil
		};
		^this.prPut(name, bus, \owned)
	}

	// Name a bus that already exists somewhere else - a module's tapBus, a
	// SuperDirt orbit, an Ndef's bus. The registry never frees it and never
	// re-allocates it: it belongs to whatever made it.
	*addBus { |name, bus|
		this.prEnsureSetup;
		if (bus.isKindOf(Bus).not) {
			("BusManager: addBus needs a Bus, got %".format(bus.asCompileString)).warn;
			^nil
		};
		^this.prPut(name, bus, \external)
	}

	// Name a bare index nobody allocated - hardware channels, or a bus you
	// are keeping track of yourself.
	*addIndex { |name, index, channels = 2, rate = \audio|
		this.prEnsureSetup;
		^this.prPut(
			name,
			Bus(rate.asSymbol, index.asInteger, channels.asInteger.max(1), this.server),
			\fixed)
	}

	// Hardware ins and outs as stereo pairs: \out1_2, \out3_4, \in1_2 ... They
	// are seeded the first time the registry is used so the window is never
	// empty and sending a module to outputs 3 and 4 needs no declaration.
	// Call it again after changing the device to pick up the new channel count.
	*addHardware {
		var opt = this.server.options;
		this.prEnsureSetup;
		this.prSeedRange("out", 0, opt.numOutputBusChannels);
		this.prSeedRange("in", opt.numOutputBusChannels, opt.numInputBusChannels);
		^this
	}

	// Take a name out of the registry. An owned bus is freed, and anything
	// still writing to that index carries on writing to it - which is why the
	// modules bound to the name are named in the warning.
	*remove { |name|
		var entry = this.at(name);
		var users;
		if (entry.isNil) {
			("BusManager: there is no bus called %".format(name.asCompileString)).warn;
			^nil
		};
		users = this.prBoundTo(entry.name);
		if (users.notEmpty) {
			("BusManager: % is being removed while % still points at it - %"
				.format(entry.name, if (users.size == 1) { "a module" } { "modules" },
					users.join(", "))).warn;
		};
		this.prDrop(entry);
		entries = entries.reject { |e| e === entry };
		this.changed(\buses);
		^entry
	}

	*rename { |from, to|
		var entry = this.at(from);
		to = to.asSymbol;
		if (entry.isNil) {
			("BusManager: there is no bus called %".format(from.asCompileString)).warn;
			^nil
		};
		if (to == entry.name) { ^entry };
		if (this.at(to).notNil) {
			("BusManager: the name % is already taken".format(to.asCompileString)).warn;
			^nil
		};
		entry.prSetName(to);
		this.changed(\buses);
		^entry
	}

	// Empty the registry: frees what it owns, lets go of what it does not.
	*clear {
		this.listenOff;
		entries.copy.do { |e| this.prDrop(e) };
		entries = [];
		this.changed(\buses);
		^this
	}

	// ---------------------------------------------------------------- lookup

	// Only a name can be looked up, but anything may be asked - code that
	// resolves "a name or an index" reaches here with the index too, and gets
	// nil rather than an error for it.
	*at { |name|
		if (name.isKindOf(Symbol).not and: { name.isKindOf(String).not }) { ^nil };
		name = name.asSymbol;
		^entries.detect { |e| e.name == name }
	}

	*bus { |name| ^this.at(name) !? (_.bus) }

	// what ModuleCommon.prBusIndex calls, and what a hand written patch wants
	*index { |name| ^this.at(name) !? (_.index) }

	*includes { |name| ^this.at(name).notNil }

	*names { ^entries.collect(_.name) }

	*size { ^entries.size }

	// the loudest channel of a bus right now - 0 unless metering is on
	*level { |name| ^this.at(name) !? (_.level) ? 0 }

	// --------------------------------------------------------------- metering

	// One small synth per audio bus, replying fifteen times a second. Off by
	// default and turned on while the window is open, since nothing else asks
	// for it.
	*meterRate { ^15 }

	*meter_ { |flag = true|
		if (flag == metering) { ^this };
		metering = flag;
		if (flag) {
			this.prEnsureSetup;
			this.prMakeLevelResponder;
			this.prStartMeters;
		} {
			this.prStopMeters;
			levelFunc.free;
			levelFunc = nil;
			entries.do(_.prResetLevels);
		};
		this.changed(\meter);
		^this
	}

	*meter { ^metering }

	// -------------------------------------------------------------- monitoring

	// Route one bus to the monitor output so you can hear what is in it. One
	// at a time: starting another stops the first.
	*listen { |name|
		var entry = if (name.isKindOf(BusManagerEntry)) { name } { this.at(name) };
		if (entry.isNil) {
			("BusManager: there is no bus called %".format(name.asCompileString)).warn;
			^nil
		};
		if (entry.isAudio.not) {
			("BusManager: % is a control bus - there is nothing to hear"
				.format(entry.name)).warn;
			^nil
		};
		// the loop you would otherwise make by clicking listen on out 1-2
		if (entry.overlaps(monitorOut, 2)) {
			("BusManager: % overlaps the monitor output (bus %) - listening to it"
				" would feed back, so it is refused".format(entry.name, monitorOut)).warn;
			^nil
		};
		this.listenOff;
		monitoring = entry;
		this.prStartMonitor;
		this.changed(\monitor);
		^entry
	}

	*listenOff {
		monitorSynth !? { |syn|
			if (this.server.serverRunning) { syn.set(\gate, 0) };
		};
		monitorSynth = nil;
		monitoring = nil;
		this.changed(\monitor);
		^this
	}

	*isListeningTo { |name| ^monitoring.notNil and: { monitoring.name == name.asSymbol } }

	// Where listening goes. Moving it onto the bus being listened to would
	// close the loop, so that stops the monitor rather than making one.
	*monitorOut_ { |index|
		monitorOut = (index ? 0).asInteger.max(0);
		if (monitoring.notNil and: { monitoring.overlaps(monitorOut, 2) }) {
			("BusManager: the monitor output now overlaps % - listening stopped"
				.format(monitoring.name)).warn;
			this.listenOff;
		} {
			monitorSynth !? (_.set(\out, monitorOut));
		};
		this.changed(\monitor);
		^this
	}

	*monitorAmp_ { |amp|
		monitorAmp = (amp ? 0).clip(0, 2);
		monitorSynth !? (_.set(\amp, monitorAmp));
		this.changed(\monitor);
		^this
	}

	// ----------------------------------------------------------------- layouts

	// Next to the class files, so layouts travel with the repository however
	// it was installed - the same trick ModuleCommon uses for presets.
	*layoutDir { ^this.filenameSymbol.asString.dirname.dirname +/+ "layouts" }

	*ensureLayoutDir {
		var dir = this.layoutDir;
		if (File.exists(dir).not) { File.mkdir(dir) };
		^dir
	}

	*layoutPath { |name|
		var p = name.asString;
		if (p.contains("/").not and: { p.contains("\\").not }) { p = this.layoutDir +/+ p };
		p = p.standardizePath;
		if (PathName(p).extension.isEmpty and: { File.exists(p).not }) { p = p ++ ".scd" };
		^p
	}

	// what the window's dropdown offers: everything in the folder, newest first
	*layouts {
		^(this.layoutDir +/+ "*").pathMatch.select { |p|
			p.last.isPathSeparator.not and: { PathName(p).fileName.beginsWith(".").not }
		}.sort { |a, b| File.mtime(a) > File.mtime(b) }
	}

	// An \external entry points at a Bus another object owns, so there is
	// nothing to write down that would still be true later - those are skipped
	// and said so.
	*asDict {
		var saved = entries.reject { |e| e.kind == \external };
		var skipped = entries.select { |e| e.kind == \external };
		if (skipped.notEmpty) {
			("BusManager: % point% at a bus owned by something else and %"
				" not saved".format(skipped.collect(_.name).join(", "),
					if (skipped.size == 1) { "s" } { "" },
					if (skipped.size == 1) { "is" } { "are" })).postln;
		};
		^(version: 1, buses: saved.collect(_.asDict))
	}

	*saveLayout { |name|
		var path;
		this.ensureLayoutDir;
		path = this.layoutPath(name ? "layout");
		File.use(path, "w", { |f| f.write(this.asDict.asCompileString) });
		("BusManager: saved layout to %".format(path)).postln;
		this.changed(\layouts);
		^path
	}

	// Owned and fixed entries are replaced by what the file holds; entries
	// pointing at somebody else's bus are left alone, since a layout never
	// knew about them in the first place.
	*loadLayout { |name|
		var path = this.layoutPath(name ? "layout");
		var data;
		if (File.exists(path).not) {
			("BusManager: no layout at %".format(path)).warn;
			^this
		};
		data = path.load;
		if (data.isNil or: { data[\buses].isNil }) {
			("BusManager: % is not a layout file".format(path)).warn;
			^this
		};
		this.prEnsureSetup;
		entries.copy.do { |e| if (e.kind != \external) { this.prDrop(e) } };
		entries = entries.select { |e| e.kind == \external };
		data[\buses].do { |d|
			if (d[\kind] == \fixed) {
				this.addIndex(d[\name], d[\index] ? 0, d[\channels] ? 2, d[\rate] ? \audio)
			} {
				this.add(d[\name], d[\channels] ? 2, d[\rate] ? \audio)
			};
		};
		("BusManager: loaded layout %".format(path)).postln;
		this.changed(\buses);
		^this
	}

	// --------------------------------------------------------------------- gui

	*gui { |x, y|
		if (guiObj.isNil) { guiObj = BusManagerGUI(x, y) } { guiObj.front };
		^guiObj
	}

	*prGuiClosed { guiObj = nil }

	// ----------------------------------------------------------------- private

	*prEnsureSetup {
		if (hooksAdded) { ^this };
		hooksAdded = true;						// before seeding, which comes back here
		bootFunc = { this.prOnBoot };
		treeFunc = { this.prOnTree };			// also runs after cmd-period
		quitFunc = { this.prOnQuit };
		ServerBoot.add(bootFunc, this.server);
		ServerTree.add(treeFunc, this.server);
		ServerQuit.add(quitFunc, this.server);
		this.addHardware;
		^this
	}

	*prRemoveHooks {
		if (hooksAdded.not) { ^this };
		ServerBoot.remove(bootFunc, this.server);
		ServerTree.remove(treeFunc, this.server);
		ServerQuit.remove(quitFunc, this.server);
		hooksAdded = false;
		^this
	}

	*prSeedRange { |word, from, count|
		var pairs = (count ? 0) div: 2;
		pairs.do { |i|
			this.addIndex(
				"%%_%".format(word, (i * 2) + 1, (i * 2) + 2).asSymbol,
				from + (i * 2), 2, \audio);
		};
		// an odd channel at the end still deserves a name
		if (count.odd) {
			this.addIndex("%%".format(word, count).asSymbol, from + count - 1, 1, \audio);
		};
	}

	*prPut { |name, bus, kind|
		var old = this.at(name);
		var entry;
		if (old.notNil) {
			this.prDrop(old);
			entries = entries.reject { |e| e === old };
		};
		idCounter = idCounter + 1;
		entry = BusManagerEntry(name.asSymbol, bus, kind, idCounter);
		entries = entries.add(entry);
		if (metering) { this.prStartMeter(entry) };
		this.changed(\buses);
		^entry
	}

	// Stop everything running on an entry and free its bus if it is ours. It
	// stays in `entries` - the callers take it out, or put it back.
	*prDrop { |entry|
		if (monitoring === entry) { this.listenOff };
		this.prStopMeter(entry);
		if (entry.isOwned) { entry.bus.free };
		^entry
	}

	// Which modules follow this name. They are dependants of this class - see
	// ModuleCommon:outBus_ - so no coupling to the module classes is needed.
	*prBoundTo { |name|
		var out = [];
		this.dependants.do { |d|
			if (d.tryPerform(\outBusName) == name) { out = out.add(d) };
		};
		^out
	}

	// ------------------------------------------------------------ meter nodes

	*prMakeLevelResponder {
		levelFunc.free;
		// [path, nodeID, replyID, id, level per channel...]
		levelFunc = OSCFunc({ |msg|
			var entry = entries.detect { |e| e.id == msg[3] };
			entry !? { entry.prSetLevels(msg.copyToEnd(4)) };
		}, '/bmLevel', this.server.addr);
	}

	// The defs have to be built and on the server before a synth asks for one,
	// so both of these do the same three steps in the same order: build what
	// is needed, send it, wait for the server to have it.
	*prStartMeters {
		if (this.server.serverRunning.not) { ^this };
		fork {
			entries.do { |e| this.prMeterDef(e.numChannels, e.rate) };
			this.prSendDefs;
			this.server.sync;
			this.prMakeGroup;
			entries.do { |e| this.prMakeMeterSynth(e) };
		};
		^this
	}

	*prStopMeters {
		entries.do { |e| this.prStopMeter(e) };
		^this
	}

	// one bus, metered on its own - a bus added while the window is open
	*prStartMeter { |entry|
		var def;
		if (metering.not or: { this.server.serverRunning.not }) { ^this };
		this.prStopMeter(entry);
		def = this.prMeterDef(entry.numChannels, entry.rate);
		fork {
			this.prSendDef(def);
			this.server.sync;
			this.prMakeGroup;
			this.prMakeMeterSynth(entry);
		};
		^this
	}

	*prMakeMeterSynth { |entry|
		// metering may have been turned off, or the bus removed, while the
		// def was on its way to the server
		if (metering.not or: { group.isNil } or: { entries.includes(entry).not }) { ^this };
		// cmd-period during a start leaves two of these in flight: whichever
		// arrives second takes over, and the first is stopped rather than lost
		this.prStopMeter(entry);
		entry.prSetMeterSynth(
			Synth(this.prMeterDef(entry.numChannels, entry.rate).name,
				[\in, entry.index, \id, entry.id], group));
		^this
	}

	*prStopMeter { |entry|
		entry.meterSynth !? { |syn|
			if (this.server.serverRunning) { syn.free };
		};
		entry.prSetMeterSynth(nil);
		entry.prResetLevels;
		^this
	}

	*prMakeGroup {
		if (this.server.serverRunning.not) { ^nil };
		// after the default group, so the meters read a bus once everything
		// that writes to it has run this block
		group ?? { group = Group.after(this.server.defaultGroup) };
		^group
	}

	// ---------------------------------------------------------- monitor node

	*prStartMonitor {
		var entry = monitoring;
		var def;
		if (entry.isNil or: { this.server.serverRunning.not }) { ^this };
		def = this.prMonitorDef(entry.numChannels);
		fork {
			this.prSendDef(def);
			this.server.sync;
			this.prMakeGroup;
			// only start it if listening was not turned off while we waited
			if (monitoring === entry and: { group.notNil }) {
				monitorSynth = Synth(def.name, [
					\in, entry.index, \out, monitorOut, \amp, monitorAmp
				], group, \addToTail);
			};
		};
		^this
	}

	// ------------------------------------------------------------- synthdefs

	// One def per channel count, built when a bus of that width first needs
	// one and kept so it can be sent again after a reboot. Building does not
	// send: prSendDef does that, and everything waits for a sync afterwards.
	*prMeterDef { |channels, rate|
		var key = "bm_meter_%_%".format(rate, channels).asSymbol;
		var replyRate = this.meterRate;
		^defs[key] ?? {
			defs[key] = if (rate == \control) {
				// a control bus has no amplitude - the value itself is the reading
				SynthDef(key, {
					SendReply.kr(Impulse.kr(replyRate), '/bmLevel',
						[\id.kr(0)] ++ In.kr(\in.kr(0), channels));
				})
			} {
				// InFeedback, not In: the meter is correct wherever the synth
				// sits relative to whatever writes the bus, at the cost of one
				// block of delay, which nobody can see at fifteen frames a second
				SynthDef(key, {
					var sig = InFeedback.ar(\in.kr(0), channels);
					SendReply.kr(Impulse.kr(replyRate), '/bmLevel',
						[\id.kr(0)] ++ Amplitude.kr(sig, 0.01, 0.3));
				})
			};
			defs[key]
		}
	}

	*prMonitorDef { |channels|
		var key = "bm_monitor_%".format(channels).asSymbol;
		^defs[key] ?? {
			defs[key] = SynthDef(key, {
				var sig = InFeedback.ar(\in.kr(0), channels);
				var env = EnvGen.kr(Env.asr(0.02, 1, 0.05), \gate.kr(1), doneAction: 2);
				sig = case
					{ channels == 1 } { sig.dup }
					{ channels == 2 } { sig }
					{ true } { Splay.ar(sig) };			// wider buses folded to stereo
				sig = sig * \amp.kr(0.5).lag(0.05) * env;
				// listening is for checking, not for mixing: it never clips the desk
				Out.ar(\out.kr(0), Limiter.ar(sig, 0.99, 0.005));
			});
			defs[key]
		}
	}

	// SynthDef:add reaches the default server; a module pointed at any other
	// one has to be sent to as well - the same two lines SampleModule needs.
	*prSendDef { |def|
		def.add;
		if (this.server != Server.default) { def.send(this.server) };
		^def
	}

	// a reboot leaves the server with no defs at all
	*prSendDefs { defs.do { |d| this.prSendDef(d) } }

	// ------------------------------------------------------- server lifecycle

	// The allocator was reset by the boot, so every bus this registry owns has
	// to be taken again. The old Bus objects are dropped rather than freed:
	// freeing an index the fresh allocator never handed out corrupts it.
	*prOnBoot {
		var moved = [];
		// this runs inside the boot: whatever happens to one bus, the rest of
		// the boot has to carry on
		entries.do { |e|
			try {
				if (e.isOwned and: { this.prReclaim(e) }) { moved = moved.add(e.name) };
			} { |err|
				("BusManager: could not take % again after the boot - %"
					.format(e.name, err.errorString)).warn;
			};
		};
		this.prSendDefs;
		// tells the window, and takes any module bound to a name along
		this.changed(\buses);
		if (moved.notEmpty) {
			("BusManager: % had to move after the boot - anything following the"
				" name went with it".format(moved.join(", "))).postln;
		};
	}

	// Ask the fresh allocator for the same index this bus had. A bus that keeps
	// its index across a reboot is one that nothing has to be told about - and
	// it keeps the registry off indices that were in use before the boot.
	// Answers whether the bus had to move.
	*prReclaim { |entry|
		var wanted = entry.index;
		var control = entry.rate == \control;
		var alloc = if (control) {
			this.server.controlBusAllocator
		} {
			this.server.audioBusAllocator
		};
		// ContiguousBlockAllocator:reserve raises rather than answering nil when
		// the allocator holds no blocks at all, which is the state a fresh boot
		// leaves it in - and an error thrown here would break the boot itself,
		// so not getting the index back has to be survivable. Bus:free returns a
		// reserved block to the allocator exactly as it does an allocated one.
		var got = try { alloc.reserve(wanted, entry.numChannels, false) } { nil };
		if (got.notNil and: { got.tryPerform(\start) == wanted }) {
			entry.prSetBus(Bus(entry.rate, wanted, entry.numChannels, this.server));
			^false
		};
		// somebody else has that index now, or this allocator cannot reserve:
		// take a new one, and let whatever follows the name hear about it
		entry.prSetBus(if (control) {
			Bus.control(this.server, entry.numChannels)
		} {
			Bus.audio(this.server, entry.numChannels)
		});
		^entry.index != wanted
	}

	// the tree was rebuilt - after a boot, or after cmd-period took every node
	*prOnTree {
		var listening = monitoring !? (_.name);
		group = nil;
		monitorSynth = nil;
		monitoring = nil;
		entries.do { |e| e.prSetMeterSynth(nil) };
		if (metering) { this.prStartMeters };
		listening !? { |n| this.listen(n) };
	}

	*prOnQuit {
		group = nil;
		monitorSynth = nil;
		monitoring = nil;
		entries.do { |e| e.prSetMeterSynth(nil); e.prResetLevels };
		this.changed(\monitor);
	}
}
