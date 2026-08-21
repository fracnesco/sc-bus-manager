// =====================================================================
// BusManagerEntry - one named bus in the registry
//
// A name, a Bus, and the answer to one question: who is allowed to free
// it. That is what `kind` is for, and getting it wrong is the only way
// this extension can damage a session, so it is the first thing here.
//
//   \owned      BusManager called Bus.audio or Bus.control for it. It is
//               freed when the entry goes and taken again after a server
//               boot - Server:newAllocators resets the language side
//               allocator, so a Bus made before a reboot can have its
//               index handed to somebody else afterwards.
//   \fixed      a bare index nobody allocated: hardware ins and outs.
//   \external   a Bus that belongs to something else - a module's
//               tapBus, a SuperDirt orbit, an Ndef. Never freed, never
//               re-allocated, never written to a layout file.
//
// `levels` holds one value per channel, written fifteen times a second
// by the meter responder in BusManager while metering is on: an
// amplitude for an audio bus, the value itself for a control bus.
// =====================================================================

BusManagerEntry {
	var <name, <bus, <kind, <id;
	var <levels;
	var <meterSynth;

	*new { |name, bus, kind, id| ^super.new.init(name, bus, kind, id) }

	init { |argName, argBus, argKind, argId|
		name = argName.asSymbol;
		bus = argBus;
		kind = argKind ? \owned;
		id = argId;
		this.prResetLevels;
		^this
	}

	// --------------------------------------------------------------- the bus

	rate { ^bus.rate }
	index { ^bus.index }
	numChannels { ^bus.numChannels }
	isAudio { ^bus.rate == \audio }
	isOwned { ^kind == \owned }

	// Does this bus overlap `channels` channels starting at `index`? Two buses
	// that overlap by even one channel are the same signal path as far as
	// feedback is concerned, which is what the monitor guard needs to know.
	overlaps { |otherIndex, otherChannels = 1|
		^(bus.index < (otherIndex + otherChannels))
			and: { otherIndex < (bus.index + bus.numChannels) }
	}

	// ------------------------------------------------------------ metering

	// the loudest channel - what a "is anything playing in there" test wants
	level { ^levels.maxItem ? 0 }

	isSilent { |floor = 0.001| ^this.level < floor }

	prSetLevels { |array| levels = array }
	prResetLevels { levels = 0 ! this.numChannels }
	prSetMeterSynth { |synth| meterSynth = synth }

	// A re-allocated bus can have a different channel count only if the entry
	// was rebuilt, but the levels array is sized from the bus either way.
	prSetBus { |newBus| bus = newBus; this.prResetLevels }
	prSetName { |newName| name = newName.asSymbol }

	// ------------------------------------------------------------- layouts

	// What a layout file holds. An \external bus belongs to another object and
	// cannot be written down - BusManager checks for that before it calls this.
	asDict {
		var dict = (name: name, channels: this.numChannels, rate: this.rate, kind: kind);
		if (kind == \fixed) { dict.put(\index, this.index) };
		^dict
	}

	printOn { |stream|
		stream << "BusManagerEntry(" << name.asCompileString << ", "
			<< this.index << ", " << this.numChannels << "ch, " << kind << ")"
	}
}
