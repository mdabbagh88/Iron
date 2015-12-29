<img src="https://img.shields.io/badge/Methods and size-core: 198 | deps: 9494 | 21 KB-e91e63.svg"></img>
<img src="https://travis-ci.org/FabianTerhorst/Iron.svg?branch=master"></img>
[![Join the chat at https://gitter.im/FabianTerhorst/Iron](https://badges.gitter.im/FabianTerhorst/Iron.svg)](https://gitter.im/FabianTerhorst/Iron?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

# Iron

Fast and easy to use NoSQL data storage

#### Add dependency

Add apt plugin in your top level gradle build file

```groovy
classpath 'com.neenbedankt.gradle.plugins:android-apt:1.8'
```

Apply apt plugin in your application gradle build file.

```groovy
apply plugin: 'com.neenbedankt.android-apt'
```

Add dependencies to your application gradle build file (the compiler and the retrofit extension is optional)

```groovy
compile 'io.fabianterhorst:iron:0.1'
compile 'io.fabianterhorst:iron-retrofit:0.1'
apt 'io.fabianterhorst:iron-compiler:0.1'
```

Initiate Iron instance with application context

```java
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Iron.init(getApplicationContext());
        //Optional if iron-retrofit is included
        Iron.setLoaderExtension(new IronRetrofit());
    }
}
```

Use the @Store annotation on any plain old Java object.

```java
@Store
public class Main {
    @Name(value = "contributors", transaction = true, listener = true, loader = true, async = true)
    ArrayList<Contributor> contributors;
    @Name("username")
    String userName;
}
```
Now you can access the generated Methods from your Main + "Store" file.

```java
 MainStore.setContributors(contributors);
```

Get value synced

```java
 MainStore.getContributors(contributors);
```

Get value asynced

```java
MainStore.getContributors(new Chest.ReadCallback<ArrayList<Contributor>>() {
    @Override
    public void onResult(ArrayList<Contributor> contributors) {
    }
});
```

Remove value

```java
 MainStore.removeContributors();
```

```java
 Iron.chest().addOnDataChangeListener(new DataChangeCallback(this) {
    @Override
    public void onDataChange(String key, Object value) {
        if(key.equals(MainStore.Keys.CONTRIBUTORS.toString())){
            Log.d(TAG, ((ArrayList<Contributor>)value).toString());
        }
    }
});
```

transactions (changes will be saved)

```java
 MainStore.executeContributorsTransaction(new Chest.Transaction<ArrayList<Contributor>>() {
    @Override
    public void execute(ArrayList<Contributor> contributors) {
    	Contributor contributor = new Contributor();
    	contributor.setName("fabianterhorst");
        contributors.add(contributor);
    }
});
```

Data change listener

```java
MainStore.addOnContributorsDataChangeListener(new DataChangeCallback<ArrayList<Contributor>>(this) {
    @Override
    public void onDataChange(ArrayList<Contributor> value) {
    }
});
```

Generic data change listener

```java
MainStore.addOnDataChangeListener(new DataChangeCallback(this) {
    @Override
    public void onDataChange(String key, Object value) {
        if(key.equals(MainStore.Keys.CONTRIBUTORS.toString()))
            //contributors changed
    }
});
```

Search for object with field and value

```java
MainStore.getContributorsForField("login", "fabianterhorst", new Chest.ReadCallback<Contributor>() {
    @Override
    public void onResult(Contributor contributor) {
        if(contributor != null)
            Log.d(TAG, contributor.toString());
    }
});
```

You can also use Iron.chest()´s methods. Your custom classes must have no-arg constructor

```java
Iron.chest().write("username", "fabian");
```

Read data objects. Iron instantiates exactly the classes which has been used in saved data. The limited backward and forward compatibility is supported.

```java
String username = Iron.chest().read("username");
```

Laod and save data with retrofit. Need loader extension to be added in application.

```java
Call<List<Repo>> reposCall = service.listRepos("fabianterhorst");
Iron.chest().load(reposCall, Repo.class);
```

Remove listener to prevent memory leaks

```java
@Override
protected void onDestroy() {
    super.onDestroy();
    MainStore.removeListener(this);
    //Iron.chest().removeListener(this);
}
```

#### Handle data structure changes
Class fields which has been removed will be ignored on restore and new fields will have their default values. For example, if you have following data class saved in Paper storage:

```java
class User {
    public String name; //Fabian
    public boolean isActive;
}
```

And then you realized you need to change the class like:

```java
class User {
    public String name; //Fabian
    // public boolean isActive; removed field
    public Location location; // New field
}
```

Then on restore the _isActive_ field will be ignored and new _location_ field will have its default value _null_.

Retrofit support
```java
Call<List<Contributor>> userCall = service.contributors("fabianterhorst", "iron");
MainStore.loadContributors(userCall);
```

with Cache:

Running [Benchmark](https://github.com/fabianterhorst/Iron/blob/master/iron/src/androidTest/java/io/fabianterhorst/iron/benchmark/Benchmark.java) on Nexus 6p, in ms:

| Benchmark                 | Iron    | [Hawk](https://github.com/orhanobut/hawk) | [sqlite](http://developer.android.com/reference/android/database/sqlite/package-summary.html) |
|---------------------------|----------|----------|----------|
| Read/write 500 contacts   | 29      | 142      |          |
| Write 500 contacts        | 27      | 60      |          |
| Read 500 contacts         | 0       | 63      |          |

Running [Benchmark](https://github.com/fabianterhorst/Iron/master/iron/src/androidTest/java/io/fabianterhorst/iron/benchmark/Benchmark.java) on Emulator, in ms:

| Benchmark                 | Iron    | [Hawk](https://github.com/orhanobut/hawk) | [sqlite](http://developer.android.com/reference/android/database/sqlite/package-summary.html) |
|---------------------------|----------|----------|----------|
| Read/write 500 contacts   | 12      | 54      |          |
| Write 500 contacts        | 11      | 25      |          |
| Read 500 contacts         | 0       | 24      |          |

### License
    Copyright 2015 Fabian Terhorst

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.