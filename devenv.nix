{ pkgs, lib, config, inputs, ... }:

let 
  unstable = import inputs.nixpkgs-unstable {
    system = pkgs.stdenv.system;
  };

in

{
  name = "goo";
  env = {
    GOO_ENVIRONMENT = "Development";
    JAVA_OPTS="--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED ";
    SBT_OPTS="--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED ";
  };

  languages.java.jdk.package = pkgs.jdk24_headless;
  languages.scala = {
    enable = true;
    sbt.enable = true;
  };

  packages = [ 
  	pkgs.git
    pkgs.kubectl
    pkgs.k9s
    pkgs.kubie
    pkgs.kubectx
    pkgs.kubernetes-helm
    pkgs.jq
	  pkgs.yq-go
  ];

  enterShell = ''
    echo "~~~ goo in $GOO_ENV ~~~"
    alias k='microk8s kubectl
  '';
  
  enterTest = ''
  	sbt test
  '';
}
