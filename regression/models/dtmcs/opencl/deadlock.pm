dtmc


module deadlock


	s : [0..2] init 0;

	[] s=0 -> (s'=1);
	[] s=1 -> 1.00 : (s'=2);

endmodule
