{ pkgs, lib, config, inputs, ... }:

{
  name = "goo";
  env.GOO_ENVIRONMENT = "Development";

  languages.java.jdk.package = pkgs.jdk21_headless;
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
  ];

  enterShell = ''
    echo "~~~ goo in $GOO_ENV ~~~"
    alias k='microk8s kubectl
  '';
  
  enterTest = ''
  	sbt test
  '';
}
