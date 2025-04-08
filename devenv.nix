{ pkgs, lib, config, inputs, ... }:

{
  name = "goo";

  env.GOO_ENV = "development";

  languages.java.jdk.package = pkgs.jdk21_headless;
  languages.scala = {
  	enable = true;
	sbt.enable = true;
  };

  packages = [ 
  	pkgs.git
  	pkgs.awscli2
  ];

  enterShell = ''
    hello
    git --version
  '';
  
  enterTest = ''
  	sbt test
  '';
}
