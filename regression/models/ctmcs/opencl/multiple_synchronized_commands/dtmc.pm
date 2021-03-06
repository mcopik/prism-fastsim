dtmc

const int max_ = 5;

// This regression model ensures that update selection from one module influences
// correctly update selection in other modules.

// Please note that flag_second will be equal to state_var1 only when first command
// is disabled and second update has been taken.

// A bug in past has been causing incorrect selection 1-1 instead of 1-0
// (there is only one command in second module).
// In such case, a state where flag_second = max_ would never be achieved.


module first

	state_var1 : [0..max_] init 0;
	// at some point we can't select this update anymore
	[update] state_var1 < max_ -> 1.0 : (state_var1'=state_var1+1);
	[update] true -> 1.0 : true;

endmodule

module second

	flag_second : [0..max_] init 0;
	// flag_second will get correct value iff 
	[update] true -> 1.0 : (flag_second' = state_var1);
	
endmodule

module third
	state_var2 : [0..max_] init 0;
	flag_third : [0..max_] init 0;
	// at some point we can't select this update anymore
	[update] state_var2 < max_ -> 1.0 : (state_var2'=state_var2+1);
	[update] state_var2 < max_ - 1 -> 1.0 : (state_var2'=state_var2+1);
	[update] true -> 1.0 : (flag_third'=max_);
	// loop
	[] flag_third=max_ -> 1.0 : true;
endmodule