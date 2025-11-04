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

  languages.java.jdk.package = unstable.jdk25_headless;
  languages.scala = {
    enable = true;
    sbt.enable = true;
  };

  packages = [ 
    pkgs.yq-go
    pkgs.git
    pkgs.jq
    pkgs.k9s
    pkgs.kubectl
    pkgs.kubectx
    pkgs.kubernetes-helm
    pkgs.kubie
  ];

  enterShell = ''
    echo "~~~ goo in $GOO_ENV ~~~"
    export KUBECONFIG="ogrodje-one-config"
    kubens goo-prod
    alias k='microk8s kubectl
  '';
  
  enterTest = ''
  	sbt test
  '';
}
