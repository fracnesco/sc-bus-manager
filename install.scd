// Symlink this repository into the SuperCollider extensions folder.
// Evaluate the block below, then recompile the class library
// (Language -> Recompile Class Library, or ctrl/cmd + shift + L).
//
// BusManager depends on nothing. If ModuleCommon happens to be installed
// as well, OneShot, LongSample and AdaptiveTextures start accepting bus
// names - m.outBus = \reverb - the moment this is compiled. Neither
// repository needs the other to compile.

(
var src = PathName(thisProcess.nowExecutingPath).pathOnly;
var dst = Platform.userExtensionDir +/+ "BusManager";

// drop the trailing slash, ln -s does not like it
if (src.endsWith("/")) { src = src.drop(-1) };

case
{ File.exists(dst) } {
	("BusManager: % already exists - remove it first if you want to relink."
		.format(dst)).warn;
}
{ true } {
	File.mkdir(Platform.userExtensionDir);
	("ln -s" + src.shellQuote + dst.shellQuote).unixCmd({ |exit|
		if (exit == 0) {
			"BusManager: linked".postln;
			("   " ++ src).postln;
			("-> " ++ dst).postln;
			"".postln;
			"Now recompile the class library: ctrl/cmd + shift + L".postln;
		} {
			("BusManager: linking failed with exit code" + exit).warn;
		};
	});
};
)


// ---------------------------------------------------------------- verify
(
var missing = [\BusManager, \BusManagerEntry, \BusManagerGUI]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("BusManager is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors.".format(missing.join(", "))).warn;
} {
	"BusManager is installed.".postln;
	"  buses:  % - %".format(BusManager.size, BusManager.names.join(", ")).postln;
	"  layouts: % in %".format(BusManager.layouts.size, BusManager.layoutDir).postln;
	"  window: BusManager.gui".postln;
	"  help:   BusManager, then cmd+d".postln;
	if (\ModuleCommon.asClass.notNil) {
		"  ModuleCommon is installed too - modules take bus names: m.outBus = \\reverb"
			.postln;
	};
};
)
