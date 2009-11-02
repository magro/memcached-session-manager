require 'buildr/java'

module Buildr

  # Provides the <code>checkstyle:html</code> and <code>[checkstyle:xml</code> tasks in project
  # and global context.
  # Require explicitly using <code>require "buildr/checkstyle"</code>.
  #
  # You can specify the base src dirs to use, normally <code>project.compile.sources</code>
  # is used for a project.
  # You can also specify which classes to include/exclude from report by
  # passing a class name regexp to the <code>checkstyle.include</code> or
  # <code>checkstyle.exclude</code> methods.
  #
  #   define 'someModule' do
  #      checkstyle.include 'some.package.*'
  #      checkstyle.include /some.(foo|bar).*/
  #      checkstyle.exclude 'some.foo.util.SimpleUtil'
  #      checkstyle.exclude /*.Const(ants)?/i
  #   end
  module Checkstyle

    VERSION = '5.0'

    class << self

      def settings
        Buildr.settings.build['checkstyle'] || {}
      end

      def version
        settings['version'] || VERSION
      end

      def dependencies
        @dependencies ||= [
          "checkstyle:checkstyle:jar:#{version}",
          'antlr:antlr:jar:2.7.6',
          'com.google.collections:google-collections:jar:0.9',
          'commons-beanutils:commons-beanutils-core:jar:1.7.0',
          'commons-cli:commons-cli:jar:1.0',
          'commons-logging:commons-logging:jar:1.0.3'
        ]
      end

      def checkstyle
        unless @checkstyle
          @checkstyle = CheckstyleConfig.new(self)
          @checkstyle.report_dir('reports/checkstyle')
          @checkstyle.data_file('reports/checkstyle.data')
        end
        @checkstyle
      end

      # Create the xml report for given config
      def create_xml(config)
        mkdir_p config.report_to.to_s
        info "Creating checkstyle xml report #{config.data_file}"
        config.ant.checkstyle :config => config.config,
          :failureProperty => config.failure_property, :failOnViolation => config.fail_on_violation,
          :maxErrors => config.errors, :maxWarnings => config.warnings do
          includes, excludes = config.includes, config.excludes
          src_dirs = config.sources
          if includes.empty? && excludes.empty?
            src_dirs.each do |src_dir|
              if File.exist?(src_dir.to_s)
                config.ant.fileset :dir=>src_dir.to_s do
                  config.ant.include :name => "**/*.java"
                end
              end
            end
          else
            includes = [//] if includes.empty?
            src_dirs.each do |src_dir|
              Dir.glob(File.join(src_dir, "**/*.java")) do |src|
                src_name = src.gsub(/#{src_dir}\/?|\.java$/, '').gsub('/', '.')
                if includes.any? { |p| p === src_name } && !excludes.any? { |p| p === src_name }
                  config.ant.fileset :file => src
                end
              end
            end
          end
          config.ant.formatter :type => :xml, :tofile => config.data_file
        end
      end

      # Create the html report for given config
      def create_html(config)
        target = config.html_out
        info "Creating checkstyle html report '#{target}'"
        config.ant.xslt :in => config.data_file, :out => target,
          :style => config.style
      end

      # Cleans the checkstyle created artifacts
      def clean(config)
        rm_rf [config.report_to, config.data_file]
      end
    end

    class CheckstyleConfig # :nodoc:

      def initialize(project)
        @project = project
      end

      attr_writer :data_file
      
      attr_reader :project
      private :project

      def failure_property
        'checkstyle.failure.property'
      end

      def report_to(file = nil)
        File.expand_path(File.join(*[report_dir, file.to_s].compact))
      end

      def html_out
        report_to('checkstyle-report.html')
      end

      def ant
        @ant ||= Buildr.ant('checkstyle') do |ant|
          cp = Buildr.artifacts(Checkstyle.dependencies).each(&:invoke).map(&:to_s).join(File::PATH_SEPARATOR)
          ant.taskdef :classpath => cp, :resource=> 'checkstyletask.properties'
        end
      end

      # :call-seq:
      #   project.checkstyle.report_dir(dir)
      #
      def report_dir(*dir)
        if dir.empty?
          @report_dir ||= project.path_to(:reports, :checkstyle)
        else
          raise "Invalid report dir '#{dir.join(', ')}" unless dir.size == 1
          @report_dir = dir[0]
          self
        end
      end

      # :call-seq:
      #   project.checkstyle.data_file(file)
      #
      def data_file(*file)
        if file.empty?
          @data_file ||= project.path_to(:reports, 'checkstyle.data')
        else
          raise "Invalid report dir '#{file.join(', ')}" unless file.size == 1
          @data_file = file[0]
          self
        end
      end

      # :call-seq:
      #   project.checkstyle.config(config_file)
      #
      def config(*file)
        if file.empty?
          @config_file
        else
          raise "Invalid config file '#{file.join(', ')}" unless file.size == 1
          @config_file = file[0]
          self
        end
      end

      # :call-seq:
      #   project.checkstyle.style(html_style)
      #
      def style(*html_style)
        if html_style.empty?
          @html_style
        else
          raise "Invalid html style file '#{html_style.join(', ')}" unless html_style.size == 1
          @html_style = html_style[0]
          self
        end
      end

      # :call-seq:
      #   project.checkstyle.fail_on_violation(fail_on_violation)
      #
      def fail_on_violation(*fail_on_violation)
        if fail_on_violation.empty?
          @fail ||= (Checkstyle.settings['fail.on.violation'] || true).to_s
        else
          raise "Invalid config file '#{fail_on_violation.join(', ')}" unless fail_on_violation.size == 1
          @fail = fail_on_violation[0]
          self
        end
      end

      # :call-seq:
      #   project.checkstyle.errors(max_errors)
      #
      def errors(*max_errors)
        if max_errors.empty?
          @max_errors ||= (Checkstyle.settings['max.errors'] || 0).to_s
        else
          raise "Invalid max errors value '#{max_errors.join(', ')}" unless max_errors.size == 1
          @max_errors = max_errors[0]
          self
        end
      end

      # :call-seq:
      #   project.checkstyle.warnings(max_warnings)
      #
      def warnings(*max_warnings)
        if max_warnings.empty?
          @max_warnings ||= (Checkstyle.settings['max.warnings'] || 0).to_s
        else
          raise "Invalid max warnings value '#{max_warnings.join(', ')}" unless max_warnings.size == 1
          @max_warnings = max_warnings[0]
          self
        end
      end

      # :call-seq:
      #   project.checkstyle.sources(*sources)
      #
      def sources(*sources)
        if sources.empty?
          @sources ||= project.compile.sources
        else
          @sources = [sources].flatten.uniq
          self
        end
      end

      # :call-seq:
      #   project.checkstyle.include(*class_patterns)
      #
      def include(*class_patterns)
        includes.push(*class_patterns.map { |p| String === p ? Regexp.new(p) : p })
        self
      end

      def includes
        @include_classes ||= []
      end

      # :call-seq:
      #   project.checkstyle.exclude(*class_patterns)
      #
      def exclude(*class_patterns)
        excludes.push(*class_patterns.map { |p| String === p ? Regexp.new(p) : p })
        self
      end

      def excludes
        @exclude_classes ||= []
      end
    end

    module CheckstyleExtension # :nodoc:
      include Buildr::Extension

      def checkstyle
        @checkstyle_config ||= CheckstyleConfig.new(self)
      end

      before_define do
        namespace 'checkstyle' do
          desc "Creates an checkstyle xml report"
          task :xml

          desc "Creates an checkstyle html report"
          task :html

          desc "Fails the build if checkstyle detected to many errors or warnings"
          task :fail_on_violation
        end
      end

      after_define do |project|
        checkstyle = project.checkstyle

        namespace 'checkstyle' do
          unless project.compile.target.nil?
            # all target files and dirs as targets
            checkstyle_xml = file checkstyle.data_file do
              Checkstyle.create_xml(checkstyle)
            end
            checkstyle_html = file checkstyle.html_out => checkstyle_xml do
              Checkstyle.create_html(checkstyle)
            end
            file checkstyle.report_to => checkstyle_html

            task :xml => checkstyle_xml
            task :html => checkstyle_html

            task :checkstyle_lenient do
              info "Setting checkstyle to ignore violations"
              project.checkstyle.fail_on_violation(false)
            end

            task :fail_on_violation => [:checkstyle_lenient, :xml] do
              property = checkstyle.ant.project.properties.find { |current| current[0] == checkstyle.failure_property }
              property = property.nil? ? nil : property[1]
              fail "To many checkstyle errors or warnings see reports in '#{checkstyle.report_to}'" if property
            end
          end

          project.clean do
            Checkstyle.clean(checkstyle)
          end
        end
      end
    end

    class Buildr::Project
      include CheckstyleExtension
    end

    namespace "checkstyle" do
      checkstyle_xml = file checkstyle.data_file do
        checkstyle.sources(Buildr.projects.map(&:checkstyle).map(&:sources).flatten)
        unless checkstyle.config
          configs = Buildr.projects.map(&:checkstyle).map(&:config).uniq.reject {|conf|
            conf.nil? || conf.strip.empty?
          }
          raise "Could not set checkstyle config from projects, existing configs: '#{configs.join(', ')}'" if configs.size != 1
          info "Setting checkstyle config to '#{configs[0]}'"
          checkstyle.config(configs[0])
        end
        create_xml(checkstyle)
      end
      checkstyle_html = file checkstyle.html_out => checkstyle_xml do
        unless checkstyle.style
          styles = Buildr.projects.map(&:checkstyle).map(&:style).uniq.reject{|style|
            style.nil? || style.strip.empty?
          }
          raise "Could not set html style from projects, existing styles: '#{styles.join(', ')}'" if styles.size != 1
          info "Setting checkstyle html style to '#{styles[0]}'"
          checkstyle.style(styles[0])
        end
        create_html(checkstyle)
      end
      file checkstyle.report_to => checkstyle_html
      
      desc "Create checkstyle xml report in #{checkstyle.report_to.to_s}"
      task :xml => checkstyle_xml

      desc "Create checkstyle html report in #{checkstyle.report_to.to_s}"
      task :html =>checkstyle_html
        
    end

    task "clean" do
      clean(checkstyle)
    end
  end
end

