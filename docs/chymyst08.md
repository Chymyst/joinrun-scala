
## Cigarette smokers

The "Cigarette smokers" problem is to implement four concurrent processes that coordinate assembly line operations to manufacture cigarettes with the 
workers smoking the individual cigarettes they manufacture. One process represents a supplier of ingredients on the assembly line, which we may call the 
pusher. The other processes are three smokers, each having an infinite supply of only one of the three required ingredients, which are matches, paper, and 
tobacco. We may give names to the smokers/workers as Keith, Slash, and Jimi. 

The pusher provides at random time intervals one unit each of two required ingredients (for example matches and paper, or paper and tobacco). The pusher is not allowed to coordinate with the smokers to use knowledge of which smoker 
needs which ingredients, he just supplies two ingredients at a time. We assume that the pusher has an infinite supply of the three 
ingredients available to him. The real life example is that of a core operating systems service having to schedule and provide limited distinct resources to 
other services where coordination of scarce resources is required.
 
Each smoker selects two ingredients, rolls up a cigarette using the third ingredient that complements the list, lits it up and smokes it. It is necessary
 for the smoker to finish his cigarette before the pusher supplies the next two ingredients (a timer can simulate the smoking activity). We can think of the 
 smoker shutting down the assembly line operation until he is done smoking.


We model the processes as follows:

| Supplier   | | Smoker 1   | |  Smoker 2    | |  Smoker 3 |
| --- | --- | --- | --- | --- | --- |  --- |
| select 2 random ingredients | | pick tobacco and paper | | pick tobacco and matches | | pick matches and paper |


Let us now figure out the chemistry that will solve this problem. We can think of the problem as a concurrent producer consumer queue with three competing 
consumers and the supplier simply produces random pairs of ingredients into the queue. For simplicity, we assume the queue has capacity 1, which is an 
assumption in the statement of the problem (smoker shuts down factory operation while he takes a smoke break, thus pausing the pusher).
 
It is important to think of a suitable data model to capture the state of the world for the problem, so we need to know when to stop and count how many 
cycles we go through, if we want to stop the computation. It may be useful to keep track of how many ingredients have been shipped or consumed but this does 
not look to be important for a minimal solution. We include inventory tracking because of some logging we do to represent the concurrent activities more 
explicitly; this inventory tracking does add a bit of complexity.

For counting, we will use a distinct molecule `count` dedicated just to that and emit it initially with a constant number. We also use a blocking molecule 
`check` that we emit when we reach a `count` of 0. This approach is same as in several other examples discussed here.
```scala
    val count = m[Int]
    val check = new EE("check") 

    site(tp) ( // reactions
          go { case pusher(???) + count(n) if n >= 1 => ??? // the supply reaction TBD
                 count(n-1) // let us include this decrement now.
             },
          go { case count(0) + check(_, r) => r() }  // note that we use mutually exclusive conditions on count in the two reactions.
    )
    // emission of initial molecules in chemistry follows
    // other molecules to emit as necessary for the specifics of this problem
    count(supplyLineSize) // if running as a daemon, we would not use the count molecule and let the example/application run for ever.
    check()

```

We now introduce one molecule for each smoker and their role should be symmetrical while capturing the information about their ingredient input requirements.
 Let us give names to smoker molecules as `Keith`, `Slash`, and `Jimi`. Let us assign one molecule per ingredient: `paper`, `matches`, and `tobacco`. This 
 will represent the last three reactions. We need to emit the molecules for `Keith`, `Slash`, and `Jimi` on start up, which can be combined (`Keith(()) + Slash(()) + Jimi(())`).

Here, we write up a helper function `enjoyAndResume` that is a refactored piece of code that is common to the three smokers, who, when receiving ingredients, make the 
 cigarette, smoke it while taking a break off work, shutting down the operations and then resuming operation to `pusher` when done, which is required as the 
 smoker must notify the `pusher` when to resume. The smoking break is a simple waste of time represented by a sleep. The smoker molecule must re-inject 
 itself once done to indicate readiness of the smoker to get back to work to process the next pair of ingredients. 
 
 Notice here that we capture a state of the shipped inventory from the ingredient molecules, all of which carry 
 the value of the inventory and echo it back to the pusher so that he knows where he is at in his bookkeeping; the smokers collaborate and don't lie so 
 simply echo back the inventory as is (note that this is not necessary if we are not interested in tracking down how many ingredients have been used in 
 manufacturing).
```scala
    def smokingBreak(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)
    def enjoyAndResume(s: ShippedInventory) = {
      smokingBreak()
      pusher(s)
    }
   site(tp) ( // reactions
     // other reactions ...
      go { case Keith(_) + tobacco(s) + matches(_) => enjoyAndResume(s); Keith() },
      go { case Slash(_) + tobacco(s) + paper(_) => enjoyAndResume(s); Slash() },
      go { case Jimi(_) + matches(s) + paper(_) => enjoyAndResume(s); Jimi()}
   )
   // other initial molecules to be emitted
   Keith(()) + Slash(()) + Jimi(())

```
Now, we need the `pusher` molecule to generate a pair of ingredients randomly at time intervals. Paying attention to the statement of the problem, we notice 
that he needs to wait for a smoker to be done, hence as stated before, we simply need to emit the `pusher` molecule from the smoker reactions (a signal in 
conventional 
terminology) and the `pusher` molecule should be emitted on start up and should respond to the count molecule to evaluate the deltas in the 
`ShippedInventory`; the active `pusher` can be thought of as representing an active factory so we must emit its molecule on start up.
 
 We represent the shipped inventory as a case class with a count for each ingredient, we call it 
`ShippedInventory`. We integrate counting as previously discussed, introduce the random selection of ingredient pairs and emit the molecules for the pair of ingredients.
```scala
  case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
  site(tp) (
      go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1 =>
        scala.util.Random.nextInt(3) match { // select the 2 ingredients randomly
          case 0 =>
            val s = ShippedInventory(t+1, p, m+1)
            tobaccoShipment(s)
            matchesShipment(s)
          case 1 =>
            val s =  ShippedInventory(t+1, p+1, m)
            tobaccoShipment(s)
            paperShipment(s)
          case _ =>
            val s = ShippedInventory(t, p+1, m+1)
            matchesShipment(s)
            paperShipment(s)
        }
        count(n-1)
      }
  )
  count(supplyLineSize) 
  pusher(ShippedInventory(0,0,0))
  // other initial molecules to be emitted (the smokers and check)
  
```

The final code looks like this:

```scala
   val supplyLineSize = 10
    def smokingBreak(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)

    case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
    // this data is only to demonstrate effects of randomization on the supply chain and make content of logFile more interesting.
    // strictly speaking all we need to keep track of is inventory. Example would work if pusher molecule value would carry Unit values instead.

    val pusher = m[ShippedInventory] // pusher means drug dealer, in classic Comp Sci, we'd call this producer or publisher.
    val count = m[Int]
    val KeithInNeed = new E("Keith obtained tobacco and matches to get his fix") // makes for more vivid tracing, could be plainly m[Unit]
    val SlashInNeed = new E("Slash obtained tobacco and matches to get his fix") // same
    val JimiInNeed = new E("Jimi obtained tobacco and matches to get his fix") // same

    val tobaccoShipment = m[ShippedInventory] // this is not particularly elegant, ideally this should carry Unit but pusher needs to obtain current state
    val matchesShipment = m[ShippedInventory] // same
    val paperShipment = m[ShippedInventory] // same

    val check = new EE("check") // blocking Unit, only blocking molecule of the example.

    val logFile = new ConcurrentLinkedQueue[String]

    site(tp) (
      go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1 =>
        logFile.add(s"$n,$t,$p,$m") // logging the state makes it easier to see what's going on, curious user may put println here instead.
        scala.util.Random.nextInt(3) match { // select the 2 ingredients randomly
          case 0 =>
            val s = ShippedInventory(t+1, p, m+1)
            tobaccoShipment(s)
            matchesShipment(s)
          case 1 =>
            val s =  ShippedInventory(t+1, p+1, m)
            tobaccoShipment(s)
            paperShipment(s)
          case _ =>
            val s = ShippedInventory(t, p+1, m+1)
            matchesShipment(s)
            paperShipment(s)
        }
        count(n-1)
      },
      go { case count(0) + check(_, r) => r() },

      go { case KeithInNeed(_) + tobaccoShipment(s) + matchesShipment(_) =>
        smokingBreak(); pusher(s); KeithInNeed()
      },
      go { case SlashInNeed(_) + tobaccoShipment(s) + paperShipment(_) =>
        smokingBreak(); pusher(s); SlashInNeed()
      },
      go { case JimiInNeed(_) + matchesShipment(s) + paperShipment(_) =>
        smokingBreak(); pusher(s); JimiInNeed()
      }
    )

    KeithInNeed(()) + SlashInNeed(()) + JimiInNeed(())
    pusher(ShippedInventory(0,0,0))
    count(supplyLineSize) // if running as a daemon, we would not use count and let the example/application run for ever.

    check()
```

There is a harder, more general treatment of the cigarette smokers problem, which has the `pusher` in charge of the rate of availability of ingredients, not 
having to wait for smokers, which the reader may think of using a producer-consumer queue with unlimited buffer in classic treatment of the problem. The 
solution is provided in code. Let us say that the change is very simple, we just need
 to have pusher emit the pusher molecule instead
 of the smokers doing so.  The pausing in the assembly line needs to be done within the `pusher` reaction, otherwise it is the same solution.