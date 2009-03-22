# some artifact related methods, and a shortcut for running a javaclass with rmi-props


def javarmi(clazzname, policy, project)
  Java::Commands.java(clazzname, :classpath => [project._('target/classes/'),project._('target/resources/'),project.compile.dependencies],
      :java_args => [
        "-Djava.rmi.server.codebase=file:" + project._('target/classes/') + '/',
        '-Djava.rmi.server.hostname=localhost',
        '-Djava.security.policy=' + project._(policy)]
  )    
end


def create_pom(art)
  artifact art do |a|
    if !File.exist?(a.name)
      # Create the directory
      mkdir_p File.dirname(a.name)
      # Create and write the pom file
      File.open a.name, "w" do |f|
        f.write <<-XML
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>#{a.group}</groupId>
  <artifactId>#{a.id}</artifactId>
  <version>#{a.version}</version>
</project>
        XML
      end
    end
  end
end

def to_artifact(a)
  (a.kind_of? String) ? artifact(a) : a
end

def to_string(a)
  a.id + ":" + a.version
end

def deptree(deps)
  deps.collect { |a| to_artifact(a) }\
    .collect{ |a|
      if a.kind_of? Array
        puts( " + " + to_string(a.first) )
        a[1, a.length - 1].collect{ |t|
          puts( "   + " + to_string(t) )
        }
      else
        puts( " + " + to_string(a) )
      end
    }
end

def checkdeps(deps)
  pairs = deps \
    .collect { |a| to_artifact(a) } \
    .inject({}) { |hash, a|
      
      #definition = lambda{ define_method(:z){ x } }
      
      if a.kind_of? Array
        a.each do |el|
          if hash.has_key?(el.id)
            hash[el.id] = [hash[el.id], a.first]
          else
            hash[el.id] = a.first
          end          
        end
      else
        if hash.has_key?(a.id)
          hash[a.id] = [hash[a.id], a]
        else
          hash[a.id] = a
        end
      end
      hash
    }

  found_dup = false
  pairs.each_pair {|key, value|
    if (value.kind_of? Array and value.length > 1)
      puts( "#{key} is provided by " + value.collect{ |v| v.id }.join(', '))
      found_dup = true
    end
  }
  if !found_dup
    puts( 'Everything\'s fine, no transitive dependency is provided by more than one artifact.')
  end
  
end