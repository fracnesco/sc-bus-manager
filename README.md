# BusManager

**Buses with names, and a window to see them in.** A SuperCollider extension.

Register buses under names, add a GUI to create new entries, preview the signal
in a bus and monitor it on the main out. Instead of remembering that the reverb
send is bus 16, you call it `\reverb` and route to it by name — and you can
watch a meter to see whether anything is actually going into it, and monitor the
signal by ear.

```supercollider
s.boot;
BusManager.add(\reverb, 2);      // allocate a stereo bus called reverb
BusManager.gui;                  // add, remove, meter, listen

{ Out.ar(BusManager.index(\reverb), PinkNoise.ar(0.1) ! 2) }.play;
```

It depends on nothing — no plugins, no other quarks. If **ModuleCommon** happens
to be installed alongside it, OneShot, LongSample and AdaptiveTextures start
accepting names wherever they take a bus — see [Modules](#modules) below.

Written for SuperCollider 3.13 on macOS. Nothing in it is platform specific
apart from the symlink in `install.scd`, which Linux shares and Windows does not.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-bus-manager.git");
thisProcess.recompile;
```

`Quarks.install` clones the repository into your `downloaded-quarks` folder and
puts it on the class path; the recompile is what makes the classes exist, and is
the same thing as `Language -> Recompile Class Library` (ctrl/cmd + shift + L).

Afterwards:

```supercollider
Quarks.update("sc-bus-manager");      // pull the newest version, then recompile
Quarks.uninstall("sc-bus-manager");   // take it off the class path
Quarks.gui;                           // see what is installed
```

The quark goes by the name of the repository, `sc-bus-manager`; the class inside
it is `BusManager`.

### From a clone

If you would rather have a working copy you can edit, clone it wherever you keep
things and link it into the extensions folder:

```sh
git clone https://github.com/fracnesco/sc-bus-manager.git
```

There is an `install.scd` in the repository that does the linking for you: open
it and evaluate it, then recompile the class library. It symlinks the folder
into `Platform.userExtensionDir` and tells you what it did.

On Windows there is no `ln -s`, so use the quark, or copy the folder into the
extensions folder by hand — `Platform.userExtensionDir.openOS` opens it.

Install it one way or the other, **not both**: two copies on the class path is a
duplicate class error at compile time.

### Check that it took

```supercollider
BusManager.names;    // the hardware ins and outs, seeded on first use
BusManager.gui;      // the window
```

Select `BusManager` and press cmd/ctrl + d for the help file, and see
[examples/bus-manager-demo.scd](examples/bus-manager-demo.scd) for a walkthrough
you can evaluate a block at a time.

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
you left it. They live in the `layouts/` folder next to the class files, wherever
the extension ended up — inside `downloaded-quarks/sc-bus-manager/` for a quark
install, inside the clone for a symlinked one. A bare name goes there and gets an
`.scd` extension; a path with a slash in it is used as given, which is the way to
keep a layout with a piece rather than with the extension.

```supercollider
BusManager.saveLayout("tonight");     // -> <extension>/layouts/tonight.scd
BusManager.loadLayout("tonight");
BusManager.saveLayout("~/Music/piece/buses.scd");
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

Saved layouts are ignored by git, so updating the extension does not touch them
and they never turn up in a pull request.

## Modules

With **ModuleCommon** installed — a separate extension of mine, and entirely
optional here — every routing argument in OneShot, LongSample and
AdaptiveTextures takes a name as readily as an index:

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
| [classes/BusManager.sc](classes/BusManager.sc) | the registry: add, remove, resolve, meter, listen, layouts, server lifecycle |
| [classes/BusManagerEntry.sc](classes/BusManagerEntry.sc) | one named bus, and who is allowed to free it |
| [classes/BusManagerGUI.sc](classes/BusManagerGUI.sc) | the window |
| [HelpSource/Classes/BusManager.schelp](HelpSource/Classes/BusManager.schelp) | the help file, cmd/ctrl + d on the class name |
| [examples/bus-manager-demo.scd](examples/bus-manager-demo.scd) | a walkthrough you can evaluate line by line |
| [install.scd](install.scd) | symlinks the repository into the extensions folder |
| [sc-bus-manager.quark](sc-bus-manager.quark) | the quark manifest, named after the repository so `Quarks` can find it |

## Issues

Bug reports and questions are welcome in
[Issues](https://github.com/fracnesco/sc-bus-manager/issues). If something
misbehaves, the post window usually has the half of the story I need.

## License

MIT — see [LICENSE](LICENSE).
