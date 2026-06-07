{
  description = "Builderhead development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        isDarwin = pkgs.stdenv.isDarwin;
        isLinux  = pkgs.stdenv.isLinux;

        # Core tools
        basicTools = with pkgs; [
          git git-lfs rlwrap
          clj-kondo clojure babashka
          jdk21 clojure-lsp neil
          code2prompt
          awscli2
          # Bayesian MCMC backend for the Clojure reproduction (orca_clj/).
          # NOTE: build models against a *writable* copy of cmdstan — the store
          # tree is read-only and its prebuilt PCH is compiler-mismatched on
          # Darwin. See orca_clj/README.md (setup-cmdstan).
          cmdstan clang gnumake
        ];

        # Container tools (commented out for now)
        # containerTools = with pkgs;
        #   if isLinux then [ docker docker-compose ] else [ docker-compose ];

        # Utility tools
        utilityTools = with pkgs; [
          plantuml watch chromedriver
        ];

	commonShellConfig = {
	  JAVA_HOME = "${pkgs.jdk21}";
	  CLOJURE_LSP_PATH = "${pkgs.clojure-lsp}/bin/clojure-lsp";

	  # Silence macOS locale warnings in nix shell
	  LANG = "en_US.UTF-8";
	  LC_ALL = "en_US.UTF-8";
	  LC_CTYPE = "en_US.UTF-8";

	  shellHook = ''
	    echo "=== Builderhead Development Environment ==="
	    echo "JDK: ${pkgs.jdk21.version}"
	    echo "Clojure LSP: $(${pkgs.clojure-lsp}/bin/clojure-lsp --version)"
	    echo "Platform: ${if isDarwin then "macOS" else "Linux"}"
	    echo ""

	    # Optional: if your host has 'op' and you want auto sign-in:
	    # if command -v op &> /dev/null; then
	    #   eval "$(op signin)" || true
	    # fi

	    echo "Development environment ready! Happy coding!"
	  '';
	};


      in {
        devShells.default = pkgs.mkShell (commonShellConfig // {
          name = "builderhead-dev";
          nativeBuildInputs =
            basicTools
            # ++ containerTools
            ++ utilityTools;
        });
      }
    );
}
