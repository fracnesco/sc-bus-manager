// =====================================================================
// BusManager demo - evaluate one block at a time (ctrl/cmd + enter)
// =====================================================================

// 1. boot, and open the window ----------------------------------------
s.boot;

BusManager.gui;

// The ins and outs of your interface are in there already, as stereo pairs.
// Metering is on while the window is open, so the meters are live from the
// start - they are just sitting at zero because nothing is playing yet.

BusManager.names;


// 2. add a bus --------------------------------------------------------
// from the window: type a name in the row at the bottom, set the channel
// count, press add. Or from code:

BusManager.add(\reverb, 2);

BusManager.index(\reverb);      // the number to route with
BusManager.at(\reverb);         // the whole entry


// 3. put something in it ----------------------------------------------
(
~noise = { Out.ar(BusManager.index(\reverb), PinkNoise.ar(0.1) ! 2) }.play;
)

// The reverb row in the window is moving now. Click **listen** on it to hear
// what is going in - it is being written to a private bus, so listening is
// the only way to hear it. Click listen again to stop.

BusManager.level(\reverb);      // the same reading, from code
BusManager.at(\reverb).levels;  // one per channel
BusManager.at(\reverb).isSilent;

BusManager.listen(\reverb);
BusManager.listenOff;

~noise.free;


// 4. something to listen to that is not yours --------------------------
// a bus another object made can be named without the registry taking it over:
// it is never freed and never re-allocated.

(
~someBus = Bus.audio(s, 2);
BusManager.addBus(\borrowed, ~someBus);
)

// a module's output, if you have OneShot or LongSample installed:
// BusManager.addBus(\drums, OneShot(\drums).tapBus);

BusManager.at(\borrowed).kind;      // -> external


// 5. actually using the names ------------------------------------------
// in a synth of your own:

(
SynthDef(\demoVerb, {
	var sig = In.ar(\in.kr(0), 2);
	Out.ar(\out.kr(0), FreeVerb.ar(sig, 0.6, 0.8) * 0.6);
}).add;
)

(
~verb = Synth(\demoVerb, [
	\in, BusManager.index(\reverb),
	\out, 0
], addAction: \addToTail);
)

~noise = { Out.ar(BusManager.index(\reverb), Dust.ar(6) ! 2 * 0.5) }.play;

// both rows move now: reverb has the dust in it, out1_2 has the reverb.

~noise.free; ~verb.free;


// 6. with the modules --------------------------------------------------
// with ModuleCommon installed, anywhere a module takes a bus it takes a name:

// m = OneShot(\drums, 16, outBus: \reverb);
// m.outBus = \out3_4;
// AdaptiveTextures(\tex, listen: \drums, out: \reverb);

// A module set to a name follows it. Reboot the server and the bus is taken
// again - the module goes with it without being told.


// 7. layouts -----------------------------------------------------------
// the set of buses you have declared, saved so a rig comes back as you left it

BusManager.saveLayout("demo");
BusManager.layouts;

BusManager.clear;               // everything gone
BusManager.loadLayout("demo");  // and back


// 8. tidying up ---------------------------------------------------------
BusManager.remove(\reverb);
BusManager.clear;
