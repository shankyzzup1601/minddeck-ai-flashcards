package com.minddeck.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PracticeQuestion(val id: String,val classLevel: String,val subject: String,val chapter: String,val prompt: String,val options: List<String>,val correct: Int,val explanation: String)
data class PracticeSession(val id: String,val questions: List<PracticeQuestion>,val answers: List<Int>,val endAt: Long,val index: Int=0,val submitted: Boolean=false) {
    val score get()=questions.indices.count {answers.getOrElse(it){-1}==questions[it].correct}
    val answered get()=answers.count {it>=0}
    fun remaining(now: Long)=((endAt-now+999)/1000).coerceAtLeast(0)
    fun choose(option: Int): PracticeSession {
        require(!submitted&&index in questions.indices&&option in questions[index].options.indices)
        return copy(answers=answers.mapIndexed {i,v->if(i==index) option else v})
    }
    fun encode(): String=JSONObject().put("id",id).put("end",endAt).put("index",index).put("submitted",submitted).put("answers",JSONArray(answers)).put("questions",JSONArray().also {a->questions.forEach {q->a.put(JSONObject().put("id",q.id).put("class",q.classLevel).put("subject",q.subject).put("chapter",q.chapter).put("prompt",q.prompt).put("options",JSONArray(q.options)).put("correct",q.correct).put("explanation",q.explanation))}}).toString()
    companion object {
        fun begin(questions: List<PracticeQuestion>,now: Long): PracticeSession {
            require(questions.isNotEmpty())
            val shuffled=questions.shuffled().map {q->val order=q.options.indices.shuffled();q.copy(options=order.map {q.options[it]},correct=order.indexOf(q.correct))}
            return PracticeSession(UUID.randomUUID().toString(),shuffled,List(shuffled.size){-1},now+shuffled.size*120_000L)
        }
        fun decode(raw: String): PracticeSession?=runCatching {
            val j=JSONObject(raw);val a=j.getJSONArray("questions")
            require(a.length() in 1..50)
            val qs=(0 until a.length()).map {i->val q=a.getJSONObject(i);val options=q.getJSONArray("options");PracticeQuestion(q.getString("id"),q.getString("class"),q.getString("subject"),q.getString("chapter"),q.getString("prompt"),(0 until options.length()).map {options.getString(it)},q.getInt("correct"),q.getString("explanation"))}
            require(qs.all {it.options.size==4&&it.correct in it.options.indices})
            val answers=j.getJSONArray("answers");require(answers.length()==qs.size)
            val picked=(0 until answers.length()).map {answers.getInt(it)};require(picked.all {it in -1..3})
            PracticeSession(j.getString("id"),qs,picked,j.getLong("end"),j.getInt("index").coerceIn(qs.indices),j.getBoolean("submitted"))
        }.getOrNull()
    }
}
object PracticeBank {
    // Original starter practice, not an official exam or a complete syllabus bank.
    val questions: List<PracticeQuestion> by lazy {data.trim().lines().filter {it.isNotBlank()}.mapIndexed {i,line->val p=line.split('|');require(p.size==9);PracticeQuestion("starter-$i","Class ${p[0]}",p[1],p[2],p[3],p.subList(4,8),0,p[8])}}
    fun fromCards(cards: List<StudyCard>,classLevel: String): List<PracticeQuestion> {
        val pool=cards.filter {it.front.isNotBlank()&&it.back.isNotBlank()}.distinctBy {it.back.trim().lowercase()}
        if(pool.size<4) return emptyList()
        return pool.distinctBy {it.front.trim().lowercase()}.take(20).map {card->val distractors=pool.filter {it.id!=card.id}.shuffled().take(3).map {it.back};PracticeQuestion("deck-${card.id}",classLevel,card.subject,card.deck,card.front,listOf(card.back)+distractors,0,card.back)}
    }
    private val data="""
11|Physics|Units and Measurements|Which is an SI base quantity?|Electric current|Force|Energy|Pressure|Electric current is an SI base quantity; the other three are derived quantities.
11|Physics|Units and Measurements|What is the SI unit of force?|newton|joule|watt|pascal|One newton is one kilogram metre per second squared.
11|Physics|Units and Measurements|What are the dimensions of velocity?|Length divided by time|Length times time|Mass divided by time|Length squared divided by time|Velocity is displacement per unit time.
11|Physics|Units and Measurements|How many significant figures are in 0.00450?|3|2|5|6|Leading zeros are not significant; the final zero after the decimal is significant.
11|Physics|Units and Measurements|Which measurement has the dimension of work?|Force times displacement|Force divided by displacement|Mass times velocity|Power times distance|Work is the scalar product of force and displacement.
12|Physics|Electric Charges and Fields|What is the SI unit of electric charge?|coulomb|volt|ohm|tesla|The SI unit of charge is the coulomb, symbol C.
12|Physics|Electric Charges and Fields|Two like electric charges generally do what?|Repel|Attract|Destroy each other|Always remain stationary|Like charges repel and unlike charges attract.
12|Physics|Electric Charges and Fields|If the distance between two point charges doubles, the force becomes what?|One quarter|One half|Double|Four times|Coulomb force varies inversely with the square of separation.
12|Physics|Electric Charges and Fields|An electric dipole moment points in which direction?|From negative to positive charge|From positive to negative charge|Perpendicular to its axis|Along every field line|The conventional dipole moment vector points from negative to positive charge.
12|Physics|Electric Charges and Fields|According to Gauss's law, total electric flux through a closed surface is what?|Enclosed charge divided by vacuum permittivity|Enclosed charge times vacuum permittivity|Electric potential times area|Always zero|The net flux equals the enclosed charge divided by epsilon zero.
11|Chemistry|Some Basic Concepts of Chemistry|One mole contains approximately how many entities?|6.022 × 10²³|6.022 × 10²⁰|3.011 × 10²³|9.109 × 10⁻³¹|Avogadro's constant is approximately 6.022 × 10²³ per mole.
11|Chemistry|Some Basic Concepts of Chemistry|What is the molar mass of water, using H=1 and O=16?|18 g/mol|16 g/mol|17 g/mol|20 g/mol|H₂O contains two hydrogen atoms and one oxygen atom: 2 + 16 = 18.
11|Chemistry|Some Basic Concepts of Chemistry|How many moles are present in 44 g of CO₂, using C=12 and O=16?|1|2|0.5|4|CO₂ has a molar mass of 44 g/mol, so 44 g is one mole.
11|Chemistry|Some Basic Concepts of Chemistry|The limiting reagent determines what?|Maximum amount of product|Colour of every product|Temperature of the room|Atomic masses of reactants|The reagent consumed first limits the theoretical product yield.
11|Chemistry|Some Basic Concepts of Chemistry|Which law states that mass is conserved in a chemical reaction?|Law of conservation of mass|Law of multiple proportions|Boyle's law|Charles's law|In a closed system, total mass before and after the reaction is the same.
12|Chemistry|Solutions|Molarity is defined as what?|Moles of solute per litre of solution|Moles of solute per kilogram of solvent|Mass of solute per mole|Volume of solvent per mole|Molarity uses the total solution volume in litres.
12|Chemistry|Solutions|Molality is defined as what?|Moles of solute per kilogram of solvent|Moles of solute per litre of solution|Moles of solvent per litre|Mass of solution per litre|Molality depends on the mass of solvent, not the solution volume.
12|Chemistry|Solutions|Which concentration unit is independent of temperature when composition is unchanged?|Molality|Molarity|Mass per unit volume|Volume percentage|Mass is unaffected by thermal expansion, so molality is temperature-independent.
12|Chemistry|Solutions|Colligative properties depend primarily on what?|Number of solute particles|Colour of the solute|Shape of the container|Chemical name alone|For ideal dilute solutions, colligative effects depend on the number of dissolved particles.
12|Chemistry|Solutions|Adding a nonvolatile solute to an ideal solvent does what to vapour pressure?|Lowers it|Always doubles it|Always makes it zero|Leaves it unchanged|The solvent mole fraction falls, lowering its vapour pressure under Raoult's law.
11|Biology|The Living World|What is a taxon?|A taxonomic group at any rank|Only a species name|Only a kingdom|A microscope part|A taxon is an actual classification group, such as Mammalia or Homo sapiens.
11|Biology|The Living World|Binomial nomenclature uses which two components?|Genus and specific epithet|Family and order|Kingdom and class|Phylum and family|A scientific species name consists of a genus name and a specific epithet.
11|Biology|The Living World|Which rank is immediately above genus?|Family|Species|Order|Class|The sequence is species, genus, family, order, class, phylum or division, kingdom.
11|Biology|The Living World|In a scientific species name, the genus starts with what?|A capital letter|A lowercase letter|A number|A punctuation mark|The genus begins with a capital letter; the specific epithet begins with lowercase.
11|Biology|The Living World|Which taxonomic rank is the most specific among these?|Species|Family|Order|Kingdom|Species is more specific than family, order or kingdom.
12|Biology|Human Reproduction|Where does fertilisation normally occur in humans?|Ampullary-isthmic junction of the oviduct|Cervix|Vagina|Uterine wall|Fertilisation usually occurs at the ampullary-isthmic junction of the fallopian tube.
12|Biology|Human Reproduction|Which hormone surge primarily triggers ovulation?|LH|Insulin|Thyroxine|Melatonin|A mid-cycle luteinising hormone surge triggers ovulation.
12|Biology|Human Reproduction|What is the ploidy of a normal human gamete?|Haploid|Diploid|Triploid|Tetraploid|Normal gametes carry one set of 23 chromosomes.
12|Biology|Human Reproduction|Implantation is the attachment of what to the endometrium?|Blastocyst|Unfertilised ovum|Sperm|Primary oocyte|The blastocyst embeds in the uterine endometrium.
12|Biology|Human Reproduction|Which structure mainly secretes progesterone after ovulation?|Corpus luteum|Seminiferous tubule|Acrosome|Cervix|The corpus luteum secretes progesterone to support the endometrium.
11|Mathematics|Sets|A set with 3 elements has how many subsets?|8|3|6|9|A set with n elements has 2 to the power n subsets.
11|Mathematics|Sets|Which symbol denotes the empty set?|∅|∈|∪|⊆|The empty set has no elements and is denoted by ∅.
11|Mathematics|Sets|If A={1,2} and B={2,3}, what is their intersection?|{2}|{1,2,3}|{1,3}|∅|The intersection contains only elements common to both sets.
11|Mathematics|Sets|If A={1,2} and B={2,3}, what is their union?|{1,2,3}|{2}|{1,3}|{2,2}|A union contains every distinct element occurring in either set.
11|Mathematics|Sets|For any set A, A intersected with the empty set is what?|∅|A|The universal set|{A}|There are no common elements between any set and the empty set.
12|Mathematics|Relations and Functions|A relation that is reflexive, symmetric and transitive is called what?|An equivalence relation|Only a function|An empty relation necessarily|An inverse function|These three properties define an equivalence relation.
12|Mathematics|Relations and Functions|A function that is both one-one and onto is called what?|Bijective|Constant|Only injective|Only surjective|A bijection is both injective and surjective.
12|Mathematics|Relations and Functions|If f(x)=2x and g(x)=x+1, what is f(g(x))?|2x+2|2x+1|x+3|x²+1|Substitute x+1 into f to get 2(x+1).
12|Mathematics|Relations and Functions|The inverse of f(x)=x+3 on the real numbers is what?|x−3|3−x|x+3|3x|Solve y=x+3 for x, giving x=y−3.
12|Mathematics|Relations and Functions|Which property means that distinct inputs have distinct outputs?|Injectivity|Surjectivity|Reflexivity|Symmetry|An injective or one-one function never maps two distinct inputs to the same output.
11|Accountancy|Introduction to Accounting|What is the basic accounting equation?|Assets = Liabilities + Capital|Assets = Capital − Liabilities|Capital = Assets + Liabilities|Assets + Capital = Liabilities|Assets are financed by owners' capital and outside liabilities.
11|Accountancy|Introduction to Accounting|The owner and the business are treated separately under which concept?|Business entity|Going concern|Consistency|Conservatism|The business entity concept separates business transactions from the owner's personal transactions.
11|Accountancy|Introduction to Accounting|An increase in an asset is normally recorded on which side?|Debit|Credit|Neither side|Both sides of the same asset|Asset accounts normally increase by debits and decrease by credits.
11|Accountancy|Introduction to Accounting|A credit sale of goods creates what?|A receivable|An immediate cash receipt|A bank loan|A drawing|The customer owes the business money, creating a receivable.
11|Accountancy|Introduction to Accounting|Owner's withdrawal for personal use is called what?|Drawings|Revenue|Purchases|Capital introduced|Drawings reduce the owner's capital in the business.
12|Accountancy|Partnership Fundamentals|A written agreement among partners is called what?|Partnership deed|Invoice|Bank statement|Trial balance|A partnership deed records agreed terms governing the partnership.
12|Accountancy|Partnership Fundamentals|Under the fixed capital method, routine partner adjustments go mainly into which account?|Current account|Realisation account|Sales account|Cash book only|Drawings, interest and profit shares are generally recorded in partners' current accounts.
12|Accountancy|Partnership Fundamentals|Interest on partners' drawings is usually what for the firm?|Income|An outside liability|A fixed asset|A purchase|Interest charged on drawings is income to the firm and charged to the partner.
12|Accountancy|Partnership Fundamentals|Goodwill is classified as what?|An intangible asset|A current liability|A cash expense in every case|A physical inventory item|Goodwill represents a nonphysical business advantage.
12|Accountancy|Partnership Fundamentals|The profit and loss appropriation account deals mainly with what?|Distribution of profit among partners|Only cash sales|Only asset purchases|Only bank reconciliation|It shows how profit is appropriated according to the partnership arrangement.
11|Business Studies|Nature and Purpose of Business|Which is an economic activity?|Selling goods for income|Playing with friends for pleasure|Helping a sibling without pay|Watching a film for relaxation|Economic activities are undertaken to earn income or a livelihood.
11|Business Studies|Nature and Purpose of Business|Commerce includes trade and what else?|Auxiliaries to trade|Only farming|Only mining|Only manufacturing|Transport, banking, insurance and similar services facilitate trade.
11|Business Studies|Nature and Purpose of Business|Which activity changes raw materials into finished goods?|Manufacturing|Banking|Warehousing|Advertising|Manufacturing transforms materials into products.
11|Business Studies|Nature and Purpose of Business|Business risk arises primarily because of what?|Uncertainty|Guaranteed profit|Perfect knowledge|Fixed outcomes|Uncertain future conditions can cause business losses.
11|Business Studies|Nature and Purpose of Business|Which is an auxiliary to trade?|Insurance|Mining|Fishing|Construction of goods|Insurance supports trade by providing cover against specified risks.
12|Business Studies|Nature and Significance of Management|Getting work done with minimum waste concerns what?|Efficiency|Only effectiveness|Only staffing|Only authority|Efficiency concerns using resources with minimal cost or waste.
12|Business Studies|Nature and Significance of Management|Achieving the intended objective concerns what?|Effectiveness|Only efficiency|Only delegation|Only discipline|Effectiveness means accomplishing the intended task or goal.
12|Business Studies|Nature and Significance of Management|Which management function decides what to do in advance?|Planning|Controlling|Staffing|Directing|Planning establishes objectives and courses of action in advance.
12|Business Studies|Nature and Significance of Management|Which function compares actual performance with standards?|Controlling|Staffing|Organising|Recruitment|Controlling measures results against standards and supports corrective action.
12|Business Studies|Nature and Significance of Management|Coordination is commonly described as what?|The essence of management|Only a production technique|Only financial accounting|Only a hiring method|Coordination integrates efforts across management functions and organisational activities.
11|Economics|Introduction to Statistics|Data collected first-hand for a particular study are called what?|Primary data|Secondary data|Grouped data necessarily|Cumulative data necessarily|Primary data are collected directly by the investigator for the current purpose.
11|Economics|Introduction to Statistics|A study covering every unit of a population is called what?|A census|A sample survey|A random sample|A pilot sample|A census covers the entire population.
11|Economics|Introduction to Statistics|Which measure is the middle value in ordered data?|Median|Mean|Range|Standard deviation|The median divides an ordered dataset into two halves.
11|Economics|Introduction to Statistics|The most frequent value is called what?|Mode|Median|Mean|Range|The mode has the highest frequency.
11|Economics|Introduction to Statistics|Which chart is typically used for categorical frequencies?|Bar chart|Frequency polygon only|Ogive only|Scatter plot only|A bar chart uses separate bars to compare categories.
12|Economics|National Income Basics|GDP measures production within which boundary?|Domestic territory|Only citizens abroad|Only government offices|Only rural areas|GDP measures final production within a country's domestic territory during a period.
12|Economics|National Income Basics|A flow variable is measured over what?|A period of time|Only one instant|Only one location|No time dimension|Income and output are flows measured over a period.
12|Economics|National Income Basics|Which is a stock variable?|Wealth at a given date|Income earned per year|Output per month|Investment per year|A stock is measured at a particular point in time.
12|Economics|National Income Basics|To avoid double counting, national output generally counts what?|Final goods and services|Every resale of intermediate inputs|All financial transfers as output|Only imported goods|Counting final output avoids repeatedly counting intermediate inputs.
12|Economics|National Income Basics|Net domestic product equals GDP minus what?|Depreciation|Exports|Imports|Direct taxes|Subtract consumption of fixed capital, or depreciation, from GDP to obtain NDP.
11|Entrepreneurship|Entrepreneurship Basics|An entrepreneur typically combines resources to do what?|Create value while bearing business uncertainty|Guarantee profit without effort|Eliminate every risk|Only consume goods|Entrepreneurs organise resources to pursue opportunities under uncertainty.
11|Entrepreneurship|Entrepreneurship Basics|Innovation involves what?|Introducing useful new ideas or improvements|Only copying without adaptation|Avoiding all change|Only reducing wages|Innovation turns new ideas or improvements into practical value.
11|Entrepreneurship|Entrepreneurship Basics|A business opportunity is best described as what?|A feasible way to meet a need and create value|Any wish without demand|A guaranteed success|Only an existing job|An opportunity links a need with a feasible value-creating response.
11|Entrepreneurship|Entrepreneurship Basics|Which is an entrepreneurial competency?|Problem solving|Avoiding feedback|Ignoring customers|Refusing to plan|Problem solving helps entrepreneurs respond to obstacles and customer needs.
11|Entrepreneurship|Entrepreneurship Basics|A social enterprise primarily combines business methods with what?|A social or environmental purpose|A guarantee of no revenue|Only personal entertainment|No customer needs|Social enterprises use business methods to pursue social or environmental goals.
12|Entrepreneurship|Entrepreneurial Opportunities|A SWOT analysis examines strengths, weaknesses and what else?|Opportunities and threats|Sales and wages|Output and turnover|Orders and taxes|SWOT considers internal strengths and weaknesses plus external opportunities and threats.
12|Entrepreneurship|Entrepreneurial Opportunities|Market research helps an entrepreneur understand what?|Customers and market conditions|Only the owner's preferences|Only office decoration|Only past salary|Market research collects information to understand demand, customers and competition.
12|Entrepreneurship|Entrepreneurial Opportunities|A value proposition explains what?|Why a customer should choose the offering|Only the legal name|Only the tax rate|Only the office location|It expresses the benefit offered and why it matters to customers.
12|Entrepreneurship|Entrepreneurial Opportunities|A prototype is mainly used to do what?|Test and refine a concept|Guarantee all future sales|Replace every customer interview|Avoid feedback|A prototype makes an idea testable before full-scale production.
12|Entrepreneurship|Entrepreneurial Opportunities|Environmental scanning looks for what?|External trends, opportunities and threats|Only indoor cleanliness|Only employee attendance|Only bookkeeping errors|It examines external factors that may affect the venture.
"""
}
