dtmc


module deadlock


	s : [0..2] init 0;

	[] s=0 -> (s'=1);
	[] s=1 -> 0.01 : (s'=0) + 0.99 : (s'=2);

endmodule
