# BusManager

Buses with names, and a window to see them in. Instead of remembering that the
reverb send is bus 16, you call it `\reverb` and route to it by name — and you
can watch a meter to see whether anything is actually going into it.

```supercollider
s.boot;
BusManager.add(\reverb, 2);      // allocate a stereo bus called reverb
BusManager.gui;                  // add, remove, meter, listen

{ Out.ar(BusManager.index(\reverb), PinkNoise.ar(0.1) ! 2) }.play;
```

It depends on nothing. If [ModuleCommon](../module%20common) happens to be
installed alongside it, OneShot, LongSample and AdaptiveTextures start accepting
names wherever they take a bus — see [modules](#modules) below.

## Install

Symlink the repository into your SuperCollider extensions folder and recompile
the class library (`Language -> Recompile Class Library`, or ctrl/cmd + shift + L).
There is an `install.scd` in the repository that does it for you: open it and
evaluate it.

## The registry

One registry per session, the way there is one server. Everything is on the
class — there is no instance to keep hold of.

```supercollider
BusManager.add(\reverb, 2);          // allocate: name, channels, \audio by default
BusManager.add(\lfo, 1, \control);   // control rate buses are named too

BusManager.index(\reverb);           // -> 16, the number to route with
BusManager.bus(\reverb);             // -> the Bus itself
BusManager.at(\reverb);              // -> the entry, with its channels and rate
BusManager.names;                    // every name, in the order they were added
BusManager.includes(\reverb);        // -> true

BusManager.rename(\reverb, \verb);
BusManager.remove(\verb);            // frees the bus, if the registry owns it
BusManager.clear;                    // empty it
```

Re-using a name replaces what was there, freeing it first, so re-evaluating a
setup block does not leak buses.

### Naming a bus you did not allocate

Not every bus should be owned by the registry. Two more doors for the ones that
belong to somebody else:

```supercollider
// a bare index: hardware channels, or a bus you track yourself
BusManager.addIndex(\desk, 8, 2);

// a Bus another object made - a module, a SuperDirt orbit, an Ndef
BusManager.addBus(\drums, OneShot(\drums).tapBus);
BusManager.addBus(\dirt1, ~dirt.orbits[0].dryBus);
BusManager.addBus(\verbNode, Ndef(\verb).bus);
```

Which door you used decides who is allowed to free the bus, and that is the one
thing this extension has to get right:

| kind | made by | freed on remove | re-allocated after a boot |
|------|---------|-----------------|---------------------------|
| `\owned` | `add` | yes | yes |
| `\fixed` | `addIndex` | no | no |
| `\external` | `addBus` | no | no |

### Hardware channels are already there

The ins and outs of your interface are seeded as stereo pairs the first time the
registry is used — `\out1_2`, `\out3_4`, `\in1_2` and so on — so sending
something to outputs 3 and 4 needs no declaration:

```supercollider
m.outBus = \out3_4;
```

If you change audio device, `BusManager.addHardware` seeds them again at the new
channel count.

## Watching a bus

Two ways to find out what is in a bus: look at it, or listen to it.

```supercollider
BusManager.meter_(true);        // start metering
BusManager.level(\reverb);      // -> 0.34, the loudest channel right now
BusManager.at(\reverb).levels;  // -> [0.34, 0.31], one per channel
BusManager.at(\reverb).isSilent;

BusManager.listen(\reverb);     // route it to the monitor output and hear it
BusManager.listenOff;
BusManager.monitorOut = 0;      // where listening goes, 0 by default
BusManager.monitorAmp = 0.5;
```

Metering is one small synth per bus, replying fifteen times a second, and it is
**off until something asks for it** — opening the window turns it on and closing
the window turns it off again. Listening is routing rather than a convenience,
so it stays up until you stop it, window or no window.

Only one bus is listened to at a time: starting another stops the first.
**Listening to a bus that overlaps the monitor output is refused** rather than
played, because that is a feedback loop — which is what clicking listen on
`\out1_2` would otherwise be.

## The window

`BusManager.gui`.

```
 bus manager          monitor out [ 0 ]  level ----   [x] meters   save layout   load layout
 --------------------------------------------------------------------------------------
  name        ch   rate     index   level                            listen   remove
  out1_2       2   audio        0   ||||......                         ( )       x
  reverb       2   audio       16   ||........                         (o)       x
  lfo          1   control     12   0.35                               ( )       x
 --------------------------------------------------------------------------------------
  add   [ name        ]   ch [ 2 ]   rate [ audio ]   [ add ]
```

- **name** is editable — type a new one and press enter to rename the bus.
- **level** is one bar per channel, so you can see which side is sounding. A
  control bus shows its value instead.
- **listen** routes that bus to the monitor output; click it again to stop.
- **x** removes the bus. If a module is still pointing at it you get told so in
  the post window rather than stopped.
- The row at the bottom adds a bus: name, channel count, rate.

## Layouts

The set of buses you have declared, saved to a file so a rig comes back the way
you left it. They live in the repository's own `layouts/` folder — a bare name
goes there and gets an `.scd` extension, a path with a slash in it is used as
given.

```supercollider
BusManager.saveLayout("tonight");     // -> <repo>/layouts/tonight.scd
BusManager.loadLayout("tonight");
BusManager.layouts;                   // what the window's dropdown offers
BusManager.layoutDir;
```

Loading replaces the buses the registry allocated or named by index, and leaves
alone the ones pointing at somebody else's bus — a layout never knew about those,
so it cannot speak for them. They are skipped on save too, and the post window
says which.

Loading is always explicit. Nothing is restored at startup by itself, so a
layout you have forgotten about can never quietly claim bus indices while you
are setting up.

## Modules

With [ModuleCommon](../module%20common) installed, every routing argument in
OneShot, LongSample and AdaptiveTextures takes a name as readily as an index:

```supercollider
m = OneShot(\drums, 16, outBus: \reverb);
m.outBus = \out3_4;
AdaptiveTextures(\tex, listen: \drums, out: \reverb);
```

**And every bus field in those windows grows a dropdown** next to the number box
— OneShot's and LongSample's **out**, and both of AdaptiveTextures' **listen bus**
and **out bus**. Type an index as before, or pick a name. Buses added while a
window is open turn up in it.

A module set to a name **follows that name**. If the bus behind it moves — which
it does when the server reboots — the module goes with it, and it does not have
to be told. Setting the field back to a plain index unbinds it again, and a name
nothing knows changes nothing at all: the module stays where it was routed.

Presets save both: the index, and the name when there is one. The name is the
half that travels, since bus 16 means nothing on another rig and `\reverb` does,
so a preset loaded with `routing: true` restores the name when the registry knows
it and falls back on the index when it does not.

Neither repository needs the other to compile. BusManager knows nothing about
the modules; ModuleCommon looks this class up by name at runtime and carries on
without it.

## Server restarts

The registry registers with `ServerBoot`, `ServerTree` and `ServerQuit`:

- a **boot** resets the language side bus allocator, so every bus the registry
  owns is taken again — asking for the same index back first, so that buses
  usually keep their number across a reboot and anything pointing at them stays
  correct;
- **cmd + period** takes every node, so the meters and the monitor are rebuilt
  after it;
- a bus that has to move announces itself in the post window, and anything
  following the name moves with it.

## Files

| file | what it holds |
|------|---------------|
| `classes/BusManager.sc` | the registry: add, remove, resolve, meter, listen, layouts, server lifecycle |
| `classes/BusManagerEntry.sc` | one named bus, and who is allowed to free it |
| `classes/BusManagerGUI.sc` | the window |
| `examples/bus-manager-demo.scd` | a walkthrough you can evaluate line by line |
