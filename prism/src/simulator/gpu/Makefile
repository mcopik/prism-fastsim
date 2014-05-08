################################################
#  NB: This Makefile is designed to be called  #
#      from the main PRISM Makefile. It won't  #
#      work on its own because it needs        #
#      various options to be passed in         #
################################################

.SUFFIXES: .o .c .cc
.PHONY:	subdirs
# Reminder: $@ = target, $* = target without extension, $< = dependency

THIS_DIR = simulator/gpu
PRISM_DIR_REL = ../../..
SUBDIRS = opencl automaton
JAVA_FILES_ALL = $(wildcard *.java)
JAVA_FILES = $(patsubst %package-info.java,,$(JAVA_FILES_ALL))
CLASS_FILES = $(JAVA_FILES:%.java=$(PRISM_DIR_REL)/$(CLASSES_DIR)/$(THIS_DIR)/%.class)

PRISM_CLASSPATH = "$(dir $(PRISM_DIR_REL))$(CLASSES_DIR)$(CLASSPATHSEP)$(dir $(PRISM_DIR_REL))lib/*"

default: all

all: checks $(CLASS_FILES) subdirs
# Try and prevent accidental makes (i.e. called manually, not from top-level Makefile)
checks:
	@if [ "$(SRC_DIR)" = "" ]; then \
	  (echo "Error: This Makefile is designed to be called from the main PRISM Makefile"; exit 1) \
	fi; 

$(PRISM_DIR_REL)/$(CLASSES_DIR)/$(THIS_DIR)/%.class: %.java
	(cd ..; $(JAVAC) -sourcepath $(dir $(THIS_DIR))$(PRISM_DIR_REL)/$(SRC_DIR) -classpath $(PRISM_CLASSPATH) -d $(dir $(PRISM_DIR_REL))$(CLASSES_DIR) $(notdir $(THIS_DIR))/$<)

subdirs:
	@$(foreach dir,$(SUBDIRS),echo Making src/$(THIS_DIR)/$(dir) ... && make -C $(dir) ;)

clean: checks
	@rm -f $(CLASS_FILES) $(PRISM_DIR_REL)/$(LIB_DIR)/$(LIBPREFIX)simengine$(LIBSUFFIX) $(O_FILES)

celan: clean

$(subdirs):
	make -C $<

#################################################
